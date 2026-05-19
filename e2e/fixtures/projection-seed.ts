import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { promisify } from 'node:util';

import { assertE2eDatabase, type E2eDatabaseTarget } from './db-guard';

const execFileAsync = promisify(execFile);

const DEFAULT_DB = {
  host: process.env.SKILLSHUB_E2E_DB_HOST ?? 'localhost',
  database: process.env.SKILLSHUB_E2E_DB_NAME ?? 'skillshub_e2e',
  activeProfiles: process.env.SKILLSHUB_E2E_ACTIVE_PROFILE ?? 'local',
};

const DEFAULT_EMBEDDING_MODEL = process.env.SKILLSHUB_E2E_GENAI_EMBEDDING_MODEL ?? 'gemini-embedding-2';
const DEFAULT_EMBEDDING_DIMENSIONS = Number(process.env.SKILLSHUB_E2E_GENAI_EMBEDDING_DIMENSIONS ?? '768');

export type SqlExecutor = (sql: string) => Promise<string>;

export type SemanticSeed = {
  content: string;
  apiKey?: string;
  model?: string;
  dimensions?: number;
};

export type ProjectionSeedInput = {
  skillId: string;
  downloadCount?: number;
  qualityScore?: number;
  semantic?: SemanticSeed;
};

export type ProjectionSeedResult = {
  downloadEventsInserted?: number;
  qualityScoresUpserted?: number;
  semanticEmbeddingUpdated?: boolean;
};

export function createProjectionSeeder(executeSql: SqlExecutor = psql) {
  return {
    async seedProjectionData(
      db: E2eDatabaseTarget,
      input: ProjectionSeedInput,
    ): Promise<ProjectionSeedResult> {
      assertE2eDatabase(db);

      const result: ProjectionSeedResult = {};

      if (input.semantic) {
        const apiKey = requireSemanticApiKey(input.semantic.apiKey);
        const embedding = await embedWithGemini({
          apiKey,
          content: input.semantic.content,
          model: input.semantic.model ?? DEFAULT_EMBEDDING_MODEL,
          dimensions: input.semantic.dimensions ?? DEFAULT_EMBEDDING_DIMENSIONS,
        });
        await upsertEmbedding(executeSql, input.skillId, input.semantic.content, embedding,
          input.semantic.model ?? DEFAULT_EMBEDDING_MODEL);
        result.semanticEmbeddingUpdated = true;
      }

      if (input.downloadCount !== undefined) {
        result.downloadEventsInserted = await seedDownloadProjection(
          executeSql,
          input.skillId,
          input.downloadCount,
        );
      }

      if (input.qualityScore !== undefined) {
        result.qualityScoresUpserted = await seedQualityProjection(
          executeSql,
          input.skillId,
          input.qualityScore,
        );
      }

      return result;
    },
  };
}

export async function seedProjectionData(
  db: E2eDatabaseTarget,
  input: ProjectionSeedInput,
): Promise<ProjectionSeedResult> {
  return createProjectionSeeder().seedProjectionData(db, input);
}

export async function seedProjectionDataForDefaultDb(
  input: ProjectionSeedInput,
): Promise<ProjectionSeedResult> {
  return seedProjectionData(DEFAULT_DB, input);
}

export function requireSemanticApiKey(apiKey = process.env.SKILLSHUB_E2E_GENAI_API_KEY): string {
  if (!apiKey || apiKey.trim() === '' || apiKey === 'e2e-placeholder-key') {
    throw new Error('semantic E2E requires SKILLSHUB_E2E_GENAI_API_KEY');
  }
  return apiKey;
}

async function seedDownloadProjection(
  executeSql: SqlExecutor,
  skillId: string,
  count: number,
): Promise<number> {
  if (!Number.isInteger(count) || count < 0) {
    throw new Error(`downloadCount must be a non-negative integer: ${count}`);
  }

  const skill = sqlLiteral(skillId);
  const eventIds = Array.from({ length: count }, (_, index) =>
    uuidFromText(`${skillId}|download|${index + 1}`));
  if (eventIds.length > 0) {
    await executeSql(`
DELETE FROM download_events
 WHERE event_id IN (${eventIds.map(sqlLiteral).join(', ')});
`);
  }

  const stdout = eventIds.length === 0 ? '0' : await executeSql(`
WITH inserted AS (
  INSERT INTO download_events (id, skill_id, version, downloaded_at, event_id, metadata)
  VALUES
${eventIds.map((eventId, index) => `    (${[
    'uuid_generate_v4()::text',
    skill,
    `COALESCE((SELECT latest_version FROM skills WHERE id = ${skill}), '1.0.0')`,
    `now() - make_interval(days => ${index % 7})`,
    sqlLiteral(eventId),
    "'{}'::jsonb",
  ].join(', ')})`).join(',\n')}
  ON CONFLICT (event_id) DO NOTHING
  RETURNING 1
)
SELECT COUNT(*)::int FROM inserted;
`);

  await executeSql(`
UPDATE skills
   SET download_count = ${count},
       updated_at = now()
 WHERE id = ${skill};
`);

  return Number(stdout.trim() || '0');
}

async function seedQualityProjection(
  executeSql: SqlExecutor,
  skillId: string,
  qualityScore: number,
): Promise<number> {
  if (!Number.isFinite(qualityScore) || qualityScore < 0 || qualityScore > 100) {
    throw new Error(`qualityScore must be between 0 and 100: ${qualityScore}`);
  }

  const versionLine = (await executeSql(`
SELECT id || E'\\t' || version
  FROM skill_versions
 WHERE skill_id = ${sqlLiteral(skillId)}
 ORDER BY published_at DESC
 LIMIT 1;
`)).trim();
  if (!versionLine) {
    throw new Error(`Cannot seed quality score without a skill version: skillId=${skillId}`);
  }

  const [skillVersionId, skillVersion] = versionLine.split('\t');
  const score = Math.round(qualityScore);
  const dimensions = JSON.stringify({
    fixture: {
      score: Math.round((score / 100) * 3),
      reasoning: 'S202 fixture projection seed.',
    },
  });
  const evaluatedAt = new Date().toISOString();
  const rows = ['VALIDATION', 'IMPLEMENTATION', 'ACTIVATION'].map(axis => ({
    id: uuidFromText(`${skillVersionId}|${axis}|s202-fixture-quality`),
    axis,
    sourceEventId: uuidFromText(`${skillId}|${axis}|s202-fixture-source`),
  }));

  await executeSql(`
INSERT INTO skill_scores
  (id, skill_id, skill_version_id, skill_version, axis, total_score, dimensions,
   evaluated_at, evaluator_version, source_event_id)
VALUES
${rows.map(row => `  (${[
    sqlLiteral(row.id),
    sqlLiteral(skillId),
    sqlLiteral(skillVersionId),
    sqlLiteral(skillVersion),
    sqlLiteral(row.axis),
    score.toFixed(2),
    `${sqlLiteral(dimensions)}::jsonb`,
    sqlLiteral(evaluatedAt),
    sqlLiteral('s202-fixture'),
    sqlLiteral(row.sourceEventId),
  ].join(', ')})`).join(',\n')}
ON CONFLICT (id) DO UPDATE
   SET total_score = EXCLUDED.total_score,
       dimensions = EXCLUDED.dimensions,
       evaluated_at = EXCLUDED.evaluated_at,
       evaluator_version = EXCLUDED.evaluator_version,
       source_event_id = EXCLUDED.source_event_id;
`);

  return rows.length;
}

async function upsertEmbedding(
  executeSql: SqlExecutor,
  skillId: string,
  content: string,
  embedding: number[],
  model: string,
): Promise<void> {
  await executeSql(`
UPDATE skills
   SET embedding_content = ${sqlLiteral(content)},
       embedding = ${sqlLiteral(`[${embedding.join(',')}]`)}::vector,
       embedding_model = ${sqlLiteral(model)},
       embedding_updated_at = now()
 WHERE id = ${sqlLiteral(skillId)};
`);
}

async function embedWithGemini(input: {
  apiKey: string;
  content: string;
  model: string;
  dimensions: number;
}): Promise<number[]> {
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(input.model)}:embedContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': input.apiKey,
      },
      body: JSON.stringify({
        model: `models/${input.model}`,
        content: { parts: [{ text: input.content }] },
        output_dimensionality: input.dimensions,
      }),
    },
  );
  if (!response.ok) {
    throw new Error(`Gemini embedding request failed: ${response.status} ${await response.text()}`);
  }
  const body = await response.json() as { embedding?: { values?: number[] }, embeddings?: Array<{ values?: number[] }> };
  const values = body.embedding?.values ?? body.embeddings?.[0]?.values;
  if (!values || values.length !== input.dimensions) {
    throw new Error(`Gemini embedding returned ${values?.length ?? 0} dimensions, expected ${input.dimensions}`);
  }
  return values;
}

async function psql(sql: string): Promise<string> {
  const { stdout } = await execFileAsync('docker', [
    'compose',
    '-f',
    'compose.e2e.yaml',
    'exec',
    '-T',
    'db',
    'psql',
    '-U',
    'skillshub',
    '-d',
    'skillshub_e2e',
    '-v',
    'ON_ERROR_STOP=1',
    '-At',
    '-c',
    sql,
  ], { cwd: process.cwd() });
  return stdout;
}

function sqlLiteral(value: string): string {
  return `'${value.replaceAll("'", "''")}'`;
}

function uuidFromText(text: string): string {
  const bytes = Buffer.from(createHash('sha256').update(text).digest().subarray(0, 16));
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  return [
    bytes.subarray(0, 4).toString('hex'),
    bytes.subarray(4, 6).toString('hex'),
    bytes.subarray(6, 8).toString('hex'),
    bytes.subarray(8, 10).toString('hex'),
    bytes.subarray(10, 16).toString('hex'),
  ].join('-') || randomUUID();
}
