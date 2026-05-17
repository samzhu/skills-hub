// S140 — Playwright fixtures helper for Skills Hub critical-path E2E.
// Derived from playwright-expert/assets/fixtures-helper-template.ts
// (per ADR-007 fixtures-patterns.md Pattern 1: backend test API).
//
// Backend exposes (under @Profile({"local","dev","e2e"}) only):
//   POST /internal/test/reset                — TRUNCATE 16 application-data tables
//   POST /internal/test/seed/skill           — uploads via SkillCommandService.uploadSkill
//   POST /internal/test/seed/download-event  — direct INSERT download_events rows
//
// Implemented by S140-T01:
//   backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java

import { test as base, expect, type APIRequestContext } from '@playwright/test';

const TEST_API_BASE = 'http://localhost:8080/internal/test';

export type SkillSeed = {
  name: string;
  description: string;
  author: string;
  authorDisplayName?: string;
  authorHandle?: string;
  authorEmail?: string;
  category: string;
  version?: string;                 // default '1.0.0' on backend
  visibility?: 'PUBLIC' | 'PRIVATE'; // default PUBLIC on backend
  skillMdContent?: string;          // when null, backend synthesises minimal SKILL.md
};

export type DownloadEventSeed = {
  skillId: string;
  count: number;
  daysAgo: number;
};

export async function resetAll(req: APIRequestContext): Promise<void> {
  // S140-T09: retry on 500 — TRUNCATE 與 AFTER_COMMIT async listener race
  // 偶發 PG deadlock（前一 test 的 async listener 仍持 RowShareLock）。後端
  // 也有 retry 但攔截不到 PG deadlock_timeout 預設 1s 的 wait。client-side
  // 4 次 retry × 500ms = 2s budget 讓 outbox 排空，足以避開 deadlock。
  let lastStatus = 0;
  let lastBody = '';
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await req.post(`${TEST_API_BASE}/reset`);
    if (res.ok()) return;
    lastStatus = res.status();
    lastBody = await res.text();
    if (lastStatus !== 500) break;
    await new Promise(r => setTimeout(r, 500));
  }
  expect.fail(`reset failed after retry: ${lastStatus} ${lastBody}`);
}

export async function seedSkill(req: APIRequestContext, data: SkillSeed): Promise<string> {
  const authorHandle = data.authorHandle ?? data.author;
  const payload = {
    ...data,
    authorDisplayName: data.authorDisplayName ?? displayNameFromAuthor(data.author),
    authorHandle,
    authorEmail: data.authorEmail ?? `${authorHandle}@example.test`,
  };
  const res = await req.post(`${TEST_API_BASE}/seed/skill`, { data: payload });
  expect(res.ok(), `seed/skill failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  return body.id as string;
}

function displayNameFromAuthor(author: string): string {
  return author
    .split(/[-_]/)
    .filter(Boolean)
    .map(part => part[0]?.toUpperCase() + part.slice(1))
    .join(' ');
}

export async function seedDownloadEvents(
  req: APIRequestContext,
  data: DownloadEventSeed,
): Promise<number> {
  const res = await req.post(`${TEST_API_BASE}/seed/download-event`, { data });
  expect(res.ok(), `seed/download-event failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  return body.count as number;
}

// Canonical fixture profiles — tag tests with `@profile-<name>` per
// playwright-expert/references/fixtures-patterns.md state taxonomy.
//
// S168: 拿掉 profile.* 內 resetAll —— auto-fixture `resetState` 已先跑（line 122）。
// 雙 reset + 內含 event drain wait 會吃掉 Playwright 30s test budget。
// Tests 仍可顯式 `await resetAll(request)` 後再 seed（如需多階段測試）。
export const profiles = {
  /** No data — first-time UX, empty-state assertions. */
  async empty(_req: APIRequestContext): Promise<void> {
    // auto-fixture resetState 已 reset；nothing to do
  },

  /** One published skill — minimal positive case (detail / download / quality score). */
  async single(req: APIRequestContext): Promise<{ skillId: string }> {
    const skillId = await seedSkill(req, {
      name: 'docker-compose-helper',
      description: 'Helper skill for orchestrating docker-compose dev stacks.',
      author: 'alice',
      category: 'DevOps',
      version: '1.0.0',
    });
    return { skillId };
  },

  /** 10 mixed skills across DevOps / Testing / Docs / DataOps — paged list, search, semantic ranking. */
  async paged(req: APIRequestContext): Promise<{ skillIds: string[] }> {
    // auto-fixture resetState 已 reset；直接 seed
    // Curated mix balances PRD P1 keyword search ("docker") + P5 semantic search
    // (隨機 cosine 排序但跨 reload 一致；spec §3 AC-5 only verifies determinism, not semantic quality)
    const seeds: SkillSeed[] = [
      { name: 'docker-compose-helper',  description: 'Orchestrates docker-compose dev stacks.', author: 'alice',   category: 'DevOps' },
      { name: 'docker-image-builder',   description: 'Builds OCI images via Buildkit.',        author: 'bob',     category: 'DevOps' },
      { name: 'docker-cleaner',         description: 'Prunes dangling images and containers.', author: 'carol',   category: 'DevOps' },
      { name: 'k8s-deploy-helper',      description: 'Deploys workloads to Kubernetes.',       author: 'dave',    category: 'DevOps' },
      { name: 'junit-test-generator',   description: 'Scaffolds JUnit 5 cases from interfaces.', author: 'eve',   category: 'Testing' },
      { name: 'pytest-runner',          description: 'Runs pytest with coverage in CI.',       author: 'frank',   category: 'Testing' },
      { name: 'eslint-config-pack',     description: 'Shared ESLint preset for TS projects.',  author: 'grace',   category: 'Lint' },
      { name: 'markdown-linter',        description: 'Lints markdown for style and links.',    author: 'heidi',   category: 'Lint' },
      { name: 'docs-publisher',         description: 'Publishes mkdocs sites to GH Pages.',    author: 'ivan',    category: 'Docs' },
      { name: 'csv-to-parquet',         description: 'Converts CSV datasets to Parquet.',      author: 'judy',    category: 'DataOps' },
    ];
    const skillIds: string[] = [];
    for (const seed of seeds) {
      skillIds.push(await seedSkill(req, seed));
    }
    return { skillIds };
  },
};

// Auto-reset before every test — pattern 1 in fixtures-patterns.md.
// Tests that need a specific seed call profiles.<name>(request) inside
// the test body or a Given step (avoids implicit shared seed state).
export const test = base.extend<{ resetState: void }>({
  resetState: [
    async ({ request }, use) => {
      await resetAll(request);
      await use();
    },
    { auto: true },
  ],
});

export { expect };
