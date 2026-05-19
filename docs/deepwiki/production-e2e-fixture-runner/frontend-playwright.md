# Frontend + Playwright Plan

## 現況

`e2e/playwright.config.ts:51-80` 目前用 `webServer` 同時啟 backend `./gradlew bootRun` 與 frontend `npm run dev`。backend env 設 `SPRING_PROFILES_ACTIVE=local,dev,e2e` (`e2e/playwright.config.ts:63-65`)。

`e2e/tests/_fixtures.ts:36-84` 每個 test 前打 `/internal/test/reset`，seed skill/download 也打 `/internal/test/*`。`e2e/tests/_fixtures.ts:138-145` 用 auto fixture 在每個 test 前 reset。

方案 D 會改成：

1. Playwright setup project 啟動/呼叫 fixture runner。
2. Browser tests 只打 frontend/backend 正式路徑。
3. Tests 讀 manifest，不直接 seed。

---

## Playwright Project Dependencies

Playwright 官方建議 project dependencies 作 setup/teardown，因為 setup 會出現在 HTML report，trace 也能保留。目標 config：

```ts
export default defineConfig({
  projects: [
    {
      name: 'setup fixtures',
      testMatch: /global\.setup\.ts/,
      teardown: 'cleanup fixtures',
    },
    {
      name: 'cleanup fixtures',
      testMatch: /global\.teardown\.ts/,
    },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['setup fixtures'],
    },
  ],
});
```

Setup test 做：

```ts
import { test as setup, expect } from '@playwright/test';
import { prepareFixtures } from '../fixtures/setup-runner';

setup('prepare fixture data', async ({ request }) => {
  const forbidden = await request.post('http://localhost:8080/internal/test/reset');
  expect(forbidden.status()).toBe(404);

  await prepareFixtures({
    baseUrl: 'http://localhost:8080',
    manifestPath: 'results/fixtures.json',
  });
});
```

這段不是最終程式碼，只是資料流草案。重點是 setup project 先驗 production app 沒有 `/internal/test/reset`，再由 runner seed。

---

## Tests 改寫方向

### 現在

```ts
const seeded = await profiles.single(request);
await page.goto(`/skills/${seeded.skillId}`);
```

`profiles.single()` 在 `e2e/tests/_fixtures.ts:98-108` 會打 `/internal/test/seed/skill`。

### 目標

```ts
const fixtures = await fixturesFor('single');
await page.goto(`/skills/${fixtures.skills[0].id}`);
```

`fixturesFor()` 只讀 `e2e/results/fixtures.json`。它不碰 backend reset/seed endpoint。

---

## Frontend Target: Dev vs Production

不考慮成本時，E2E 應有兩個 target：

| Target | 服務 | 用途 |
|---|---|---|
| Fast local E2E | Production backend image + Vite dev server | 開發時快，保留 Vite HMR/錯誤 overlay。 |
| Release E2E | Production backend image serving copied `frontend/dist` | 發版 gate；測到與部署相同的 static assets。 |

`frontend/src/api/client.ts:1-5` 用相對 `/api/v1`，`frontend/vite.config.ts:17-37` 在 dev server proxy `/api/v1`、`/actuator` 等到 backend。Release target 則由 Spring Boot serve copied static assets；`cloudbuild.yaml:56-66` 已做 dist copy。

---

## Parallelism

現在 `e2e/playwright.config.ts:14-19` 固定 `workers: 1`，原因是所有 tests 共用同一個 backend state，reset/seed 會互踩。

方案 D 有兩種層級：

1. 第一版仍 `workers: 1`，但 reset/seed 移到 setup project 一次執行，tests 只讀同一份 seeded dataset。
2. 進階版每個 Playwright project / shard 建自己的 DB/schema + fixture manifest，才能提高 workers。

若要 parallel，fixture manifest 必須帶 `runId` / `schema`，backend container 也要指向該 schema。否則仍會共用 DB。

---

## Teardown

Teardown project 做：

```ts
import { test as teardown } from '@playwright/test';
import { cleanupFixtures } from '../fixtures/setup-runner';

teardown('destroy fixture environment', async () => {
  await cleanupFixtures({ manifestPath: 'results/fixtures.json' });
});
```

若使用 disposable container，teardown 可交給 Docker Compose / Testcontainers lifecycle。Docker/Testcontainers 官方定位是用 Docker container 啟動 throwaway real services；這正適合 E2E DB。
