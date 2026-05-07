// S140 critical-path E2E — AC-5 (PRD P5: Semantic search routing).
//
// /search?q=... 路由 → SearchResultsPage → useSemanticSearch hook → backend
// SemanticSearchService → vector_store cosine search。E2E profile：
//   1) E2EEmbeddingConfig.@Primary stub embedder 取代 noOp / Google bean
//   2) skillshub.search.semantic-similarity-threshold = 0.0 override 預設 0.3
//      （Spring AI 2.0.0-M5 強制 [0,1]；threshold=0.0 = 接受 cosine ≥ 0；stub
//       random ±0.1 統計上 ~半數通過，AC-5 只驗 ≥1 結果 + reload 順序 deterministic）
//
// **AC-5 不驗 semantic 質量** — POC 證實 Random(hashCode) 排序與人類直覺相反
// （docker 對「容器/部署」query cosine 最低 -0.058 / junit 最高 +0.034）。
// 只驗：① semantic 路由觸發 ② 結果非空 ③ 跨 reload 順序 deterministic。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-5: 自然語言查詢觸發語意搜尋路徑並回傳穩定排序結果 @S140 @ac-5 @happy-path @profile-paged', async ({
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

    await test.step('When user opens /search with natural-language query "我想把應用部署到容器環境"', async () => {
      const query = encodeURIComponent('我想把應用部署到容器環境');
      await page.goto(`/search?q=${query}`);

      // 等 semantic search 結果出現（resultsLoading 結束）；non-empty list
      await expect(page.getByText(/找到/)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(/0 個相關技能/)).not.toBeVisible();  // 防 0 results 路徑

      // 收集第一次的順序：所有 SkillCard h3 names
      const headings1 = page.getByRole('article').locator('h3');
      const count1 = await headings1.count();
      expect(count1, '第一次 query 結果應 ≥ 1 個 skill').toBeGreaterThan(0);
      firstOrderNames = await headings1.allTextContents();
    });

    await test.step('Then 結果非空 + 跨 reload 順序完全相同（H3 deterministic verification）', async () => {
      // Reload 後重新 query（同一 URL）→ stub embedder 同 input.hashCode → 同 vector → 同 cosine ranking
      await page.reload();
      await expect(page.getByText(/找到/)).toBeVisible({ timeout: 15_000 });

      const headings2 = page.getByRole('article').locator('h3');
      const secondOrderNames = await headings2.allTextContents();

      expect(secondOrderNames, '跨 reload 結果順序必須完全相同（POC H3 determinism）')
        .toEqual(firstOrderNames);
      expect(secondOrderNames.length, '跨 reload 結果筆數一致').toBe(firstOrderNames.length);
    });
  });
});
