import { expect, test } from '@playwright/test';

import { createProjectionSeeder, type SqlExecutor } from './projection-seed';

const e2eDb = {
  host: 'localhost',
  database: 'skillshub_e2e',
  activeProfiles: 'local',
};

test('@S202 @AC-S202-6: refuses projection seed when DB guard fails', async () => {
  const executed: string[] = [];
  const executor: SqlExecutor = async (sql) => {
    executed.push(sql);
    return '';
  };

  const seeder = createProjectionSeeder(executor);

  await expect(seeder.seedProjectionData({
    host: 'prod-db',
    database: 'skillshub',
    activeProfiles: 'prod',
  }, {
    skillId: '11111111-1111-4111-8111-111111111111',
    downloadCount: 5,
  })).rejects.toThrow(/Refusing to reset non-e2e database/);

  expect(executed).toEqual([]);
});

test('@S202 @AC-S202-4: writes download and score projection rows for manifest skill', async () => {
  const executed: string[] = [];
  const executor: SqlExecutor = async (sql) => {
    executed.push(sql);
    if (sql.includes('FROM skill_versions')) {
      return '22222222-2222-4222-8222-222222222222\t1.0.0';
    }
    if (sql.includes('download_events')) {
      return '5';
    }
    return '';
  };

  const seeder = createProjectionSeeder(executor);
  const result = await seeder.seedProjectionData(e2eDb, {
    skillId: '11111111-1111-4111-8111-111111111111',
    downloadCount: 5,
    qualityScore: 92,
  });

  expect(result.downloadEventsInserted).toBe(5);
  const sql = executed.join('\n');
  expect(sql).toContain('INSERT INTO download_events');
  expect(sql).toContain('UPDATE skills');
  expect(sql).toContain('download_count');
  expect(sql).toContain('INSERT INTO skill_scores');
  expect(sql).not.toContain('INSERT INTO skills ');
  expect(sql).not.toContain('INSERT INTO skill_versions');
  expect(sql).not.toContain('INSERT INTO domain_events');
  expect(sql).not.toContain('INSERT INTO event_publication');
});

test('@S202 @AC-S202-4: semantic fixture requires SKILLSHUB_E2E_GENAI_API_KEY', async () => {
  const executed: string[] = [];
  const executor: SqlExecutor = async (sql) => {
    executed.push(sql);
    return '';
  };

  const seeder = createProjectionSeeder(executor);

  await expect(seeder.seedProjectionData(e2eDb, {
    skillId: '11111111-1111-4111-8111-111111111111',
    semantic: {
      content: 'title: docker-compose-helper | text: Helper skill for docker compose stacks.',
      apiKey: '',
    },
  })).rejects.toThrow('semantic E2E requires SKILLSHUB_E2E_GENAI_API_KEY');

  expect(executed).toEqual([]);
});
