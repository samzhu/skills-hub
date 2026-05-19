import { expect, test as setup, type APIRequestContext } from '@playwright/test';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { promisify } from 'node:util';

import { assertE2eDatabase } from './db-guard';
import { type FixtureManifest, writeManifest } from './manifest';

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

  const manifest: FixtureManifest = {
    runId: randomUUID(),
    baseUrl,
    createdAt: new Date().toISOString(),
    profiles: {
      empty: {
        skillIds: [],
      },
    },
    skills: {},
  };
  await writeManifest(manifest);

  expect(manifest.runId).toBeTruthy();
  expect(manifest.baseUrl).toBe(baseUrl);
  expect(manifest.profiles.empty).toEqual({ skillIds: [] });
  console.log('S202 T03 POC PASS');
});

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
