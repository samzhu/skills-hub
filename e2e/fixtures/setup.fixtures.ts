import { expect, test as setup, type APIRequestContext } from '@playwright/test';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { promisify } from 'node:util';

import { assertE2eDatabase } from './db-guard';
import { type FixtureManifest, type FixtureSkill, writeManifest } from './manifest';
import { uploadSkillFixture, type SkillSeed } from './production-api-seed';
import { seedProjectionDataForDefaultDb } from './projection-seed';

const execFileAsync = promisify(execFile);

const RESET_ALLOWLIST = [
  'skills',
  'skill_versions',
  'skill_scores',
  'skill_subscriptions',
  'skill_grants',
  'collections',
  'collection_skills',
  'download_events',
  'domain_events',
  'event_publication',
  'notifications',
  'notification_preferences',
  'requests',
  'request_votes',
  'request_comments',
  'reviews',
  'flags',
  'users',
  'groups',
  'group_closure',
  'group_members',
];

setup('@S202 @AC-S202-3 @AC-S202-4: production app omits test reset route and writes fixture manifest', async ({ request }, testInfo) => {
  const baseUrl = String(testInfo.project.use.baseURL ?? 'http://localhost:8080');

  await assertForbiddenResetRouteAbsent(request);
  assertE2eDatabase({
    host: process.env.SKILLSHUB_E2E_DB_HOST ?? 'localhost',
    database: process.env.SKILLSHUB_E2E_DB_NAME ?? 'skillshub_e2e',
    activeProfiles: process.env.SKILLSHUB_E2E_ACTIVE_PROFILE ?? 'local',
  });

  await waitForEventPublications();
  await resetDatabase();

  const manifest = await buildManifest(request, baseUrl);
  await seedReadSideFixtures(manifest);
  await writeManifest(manifest);

  expect(manifest.runId).toBeTruthy();
  expect(manifest.baseUrl).toBe(baseUrl);
  expect(manifest.profiles.empty).toEqual({});
  expect(manifest.profiles.single?.skill.id).toMatch(/^[0-9a-f-]{36}$/i);
  expect(manifest.profiles.single?.skill.expectedDownloadCount).toBe(5);
  expect(manifest.profiles.single?.skill.expectedQualityScore).toBe(92);
  expect(manifest.profiles.paged?.byName['docker-compose-helper'].id).toBe(manifest.profiles.single?.skill.id);
  console.log('S202 T04 POC PASS');
  console.log('S202 T05 POC PASS');
});

async function buildManifest(request: APIRequestContext, baseUrl: string): Promise<FixtureManifest> {
  const single = await uploadSkillFixture(request, {
    asUser: 'developer',
    name: 'docker-compose-helper',
    description: 'Helper skill for orchestrating docker-compose dev stacks.',
    category: 'DevOps',
    version: '1.0.0',
  });

  const pagedSeeds: SkillSeed[] = [
    { name: 'docker-image-builder', description: 'Builds OCI images via Buildkit.', category: 'DevOps' },
    { name: 'docker-cleaner', description: 'Prunes dangling images and containers.', category: 'DevOps' },
    { name: 'k8s-deploy-helper', description: 'Deploys workloads to Kubernetes.', category: 'DevOps' },
    { name: 'junit-test-generator', description: 'Scaffolds JUnit 5 cases from interfaces.', category: 'Testing' },
    { name: 'pytest-runner', description: 'Runs pytest with coverage in CI.', category: 'Testing' },
    { name: 'eslint-config-pack', description: 'Shared ESLint preset for TS projects.', category: 'Lint' },
    { name: 'markdown-linter', description: 'Lints markdown for style and links.', category: 'Lint' },
    { name: 'docs-publisher', description: 'Publishes mkdocs sites to GH Pages.', category: 'Docs' },
    { name: 'csv-to-parquet', description: 'Converts CSV datasets to Parquet.', category: 'DataOps' },
  ];
  const paged = [single];
  for (const seed of pagedSeeds) {
    paged.push(await uploadSkillFixture(request, {
      asUser: 'developer',
      version: '1.0.0',
      ...seed,
    }));
  }
  const byName = Object.fromEntries(paged.map(skill => [skill.name, skill]));
  const skills = Object.fromEntries(paged.map(skill => [skill.id, skill])) as Record<string, FixtureSkill>;

  return {
    runId: randomUUID(),
    baseUrl,
    createdAt: new Date().toISOString(),
    profiles: {
      empty: {},
      single: {
        skill: single,
      },
      paged: {
        skills: paged,
        byName,
      },
    },
    skills,
  };
}

async function seedReadSideFixtures(manifest: FixtureManifest): Promise<void> {
  const single = manifest.profiles.single?.skill;
  if (!single) {
    throw new Error('Cannot seed S202 projection data without profiles.single.skill');
  }

  await seedProjectionDataForDefaultDb({
    skillId: single.id,
    downloadCount: 5,
    qualityScore: 92,
  });
  single.expectedDownloadCount = 5;
  single.expectedQualityScore = 92;
  manifest.skills[single.id] = single;
  manifest.profiles.paged!.byName[single.name] = single;

  if (process.env.SKILLSHUB_E2E_SEMANTIC_FIXTURES === 'true') {
    const skills = manifest.profiles.paged?.skills ?? [];
    for (const skill of skills) {
      await seedProjectionDataForDefaultDb({
        skillId: skill.id,
        semantic: {
          content: `title: ${skill.name} | text: ${skill.description}`,
        },
      });
    }
  }
}

async function assertForbiddenResetRouteAbsent(request: APIRequestContext): Promise<void> {
  const response = await request.post('/internal/test/reset');
  const status = response.status();
  if (status >= 200 && status < 400) {
    throw new Error(`production app exposes forbidden test route: POST /internal/test/reset returned ${status}`);
  }
  expect([404, 405], `POST /internal/test/reset should be absent, got ${status}`).toContain(status);
}

async function waitForEventPublications(maxWaitMs = 15_000): Promise<void> {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    const { stdout } = await psql('SELECT count(*)::int FROM event_publication WHERE completion_date IS NULL');
    const pending = Number(stdout.trim() || '0');
    if (pending === 0) return;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for event_publication pending rows to drain after ${maxWaitMs}ms`);
}

async function resetDatabase(): Promise<void> {
  await psql(`TRUNCATE TABLE ${RESET_ALLOWLIST.join(', ')} RESTART IDENTITY CASCADE`);
}

async function psql(sql: string): Promise<{ stdout: string; stderr: string }> {
  return execFileAsync('docker', [
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
}
