// S140 critical-path E2E — AC-1 (PRD P1: Browse + search).
//
// S202 後 production image 不再含 E2EEmbeddingConfig；V07 full gate 需
// SKILLSHUB_E2E_GENAI_API_KEY 讓 fixture runner 寫入 semantic projection rows。
// S189 後 /browse 有輸入時只打 semantic API，不再走 keyword fallback。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-1: 搜尋輸入顯示 semantic 技能列表 @S140 @ac-1 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given platform seeded with paged skills (3+ 含 docker)', async () => {
      await profiles.paged(request);
    });

    const semanticRequests: string[] = [];
    const keywordRequests: string[] = [];

    page.on('request', (routeRequest) => {
      const url = routeRequest.url();
      if (url.includes('/api/v1/search/semantic?q=')) {
        semanticRequests.push(url);
      }
      if (url.includes('/api/v1/skills?keyword=')) {
        keywordRequests.push(url);
      }
    });

    await test.step('When user opens HomePage and types "docker" into search', async () => {
      // S096e1：/ 是 LandingPage（curated subset），/browse 才是 HomePage（list / search）。
      await page.goto('/browse');
      // 先等任意 SkillCard 進畫面（hydration 完成證據）— HomePage 載入 = 10 skill API
      // round-trip + react-query 緩存填裝 + 卡片 render，5s 預設不夠（10 seed 大；
      // CI/local cold start 第一個 spec 通常吃 8-12s）。
      await expect(
        page.getByRole('heading', { level: 3, name: 'docker-compose-helper' }),
      ).toBeVisible({ timeout: 15_000 });
      // 再確認總數進入兩位數（filter inactive 走「共 N 個技能」分支）
      await expect(page.getByText(/共\s*1[0-9]\s*個技能/)).toBeVisible();
      const semanticResponse = page.waitForResponse((response) =>
        response.url().includes('/api/v1/search/semantic?q=docker') && response.status() === 200,
      );
      await page.getByRole('searchbox').fill('docker');
      const body = await (await semanticResponse).json() as { content?: unknown[] };
      expect(body.content?.length ?? 0, 'semantic docker query should return at least one result').toBeGreaterThan(0);
    });

    await test.step('Then semantic 結果列表顯示，且不呼叫 keyword API', async () => {
      await expect(page.getByText(/已載入\s+[1-9]\d*\s+個相關技能/)).toBeVisible();
      await expect.poll(
        () => semanticRequests.length,
        { message: '/browse search should request semantic endpoint', timeout: 15_000 },
      ).toBeGreaterThan(0);
      expect(keywordRequests, '/browse search must not call keyword API').toHaveLength(0);

      // 真 Gemini embedding 只保證相關結果非空；docker 查詢仍應至少看得到代表性 docker cards。
      await expect(page.getByRole('heading', { level: 3, name: 'docker-compose-helper' })).toBeVisible();
      await expect(page.getByRole('heading', { level: 3, name: 'docker-image-builder' })).toBeVisible();

      // 結構欄位 spot-check：每張 SkillCard 的 author/category/risk/download 都應渲染
      // （抽 docker-compose-helper 一張驗 SkillCard 結構完整 — 不重複測 3 張免拖慢）
      const composeCard = page
        .getByRole('article')
        .filter({ has: page.getByRole('heading', { level: 3, name: 'docker-compose-helper' }) });
      await expect(composeCard.getByText('Dev-042')).toBeVisible();           // mock OAuth author display name
      await expect(composeCard.getByText('DevOps', { exact: true })).toBeVisible();  // category pill
      await expect(composeCard.getByText('v1.0.0')).toBeVisible();            // version pill
    });
  });
});
