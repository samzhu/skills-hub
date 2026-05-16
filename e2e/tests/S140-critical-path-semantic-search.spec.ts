// S140 critical-path E2E — AC-5 (PRD P5: Semantic search routing).
//
// /browse 搜尋列 → useSemanticSearch hook → backend
// SemanticSearchService → vector_store cosine search。E2E profile：
//   1) E2EEmbeddingConfig.@Primary word-overlap stub embedder（S168 升級）
//   2) skillshub.search.semantic-similarity-threshold = 0.1（過濾雜訊但保留 overlap）
//
// **AC-5 不驗 semantic 質量** — 只驗：① /browse 觸發 semantic route ② 結果非空 ③ 跨 reload
// 順序 deterministic。query 用英文 NL（"images and containers in CI"），跟 paged
// seed 描述（docker-cleaner "Prunes dangling images and containers." / pytest-runner
// "Runs pytest with coverage in CI." 等）有 token overlap → stub cosine 通過 0.1
// threshold；Chinese query 需走 production Gemini，LAB 部署驗證。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-5: 自然語言查詢觸發語意搜尋路徑並回傳穩定排序結果 @S140 @S178 @ac-5 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given platform seeded with 10 skills (paged profile, mixed categories)', async () => {
      await profiles.paged(request);
      // SearchProjection async listener 處理 SkillCreatedEvent → vector_store row insert
      // 加 buffer 等 Modulith outbox AFTER_COMMIT listener catch up（10 skill seed × 平均 200ms async listener）
      await page.waitForTimeout(2000);
    });

    let firstOrderNames: string[] = [];
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

    await test.step('When user opens /browse and types natural-language query "images and containers in CI"', async () => {
      await page.goto('/browse');
      await page.getByPlaceholder('描述你想完成的任務或搜尋技能...').fill('images and containers in CI');

      // 等 semantic search 結果出現（resultsLoading 結束）；non-empty list
      await expect(page.getByText(/找到/)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(/0 個相關技能/)).not.toBeVisible();  // 防 0 results 路徑
      await expect.poll(
        () => semanticRequests.length,
        { message: '/browse search should request semantic endpoint', timeout: 15_000 },
      ).toBeGreaterThan(0);
      expect(keywordRequests, '/browse search must not call keyword API').toHaveLength(0);

      // 收集第一次的順序：所有 SkillCard h3 names
      const headings1 = page.getByRole('article').locator('h3');
      const count1 = await headings1.count();
      expect(count1, '第一次 query 結果應 ≥ 1 個 skill').toBeGreaterThan(0);
      firstOrderNames = await headings1.allTextContents();
    });

    await test.step('Then 結果非空 + 跨 reload 順序完全相同（H3 deterministic verification）', async () => {
      semanticRequests.length = 0;
      keywordRequests.length = 0;

      // /browse 不把 query 寫進 URL；reload 後重打同一段文字，stub embedder 仍會回同 ranking。
      await page.reload();
      await page.getByPlaceholder('描述你想完成的任務或搜尋技能...').fill('images and containers in CI');
      await expect(page.getByText(/找到/)).toBeVisible({ timeout: 15_000 });
      await expect.poll(
        () => semanticRequests.length,
        { message: 'reload + typed query should request semantic endpoint again', timeout: 15_000 },
      ).toBeGreaterThan(0);
      expect(keywordRequests, 'reload + typed query must still avoid keyword API').toHaveLength(0);

      const headings2 = page.getByRole('article').locator('h3');
      const secondOrderNames = await headings2.allTextContents();

      expect(secondOrderNames, '跨 reload 結果順序必須完全相同（POC H3 determinism）')
        .toEqual(firstOrderNames);
      expect(secondOrderNames.length, '跨 reload 結果筆數一致').toBe(firstOrderNames.length);
    });
  });
});
