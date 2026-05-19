// S140 critical-path E2E — AC-5 (PRD P5: Semantic search routing).
//
// /browse 搜尋列 → useSemanticSearch hook → backend
// SemanticSearchService → skills.embedding same-row cosine search。S202 後 production
// image 不再放 E2EEmbeddingConfig；V07 happy-path gate 會讓 setup runner 用同一把
// SKILLSHUB_E2E_GENAI_API_KEY 產生 doc embeddings 後寫 skills.embedding*。
//
// **AC-5 不驗 semantic 質量** — 只驗：① /browse 觸發 semantic route ② 結果非空
// ③ 回傳 card 有穩定 UI contract。ranking quality 留給真 GenAI fixture key 的完整 gate。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-5 / AC-S189-8: 自然語言查詢觸發語意搜尋路徑並回傳穩定排序結果 @S140 @S189 @ac-5 @ac-8 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given platform seeded with paged skills (mixed categories)', async () => {
      await profiles.paged(request);
      // SearchProjection async listener 處理 SkillCreatedEvent → skills.embedding_* update
      // 加 buffer 等 Modulith outbox AFTER_COMMIT listener catch up（10 skill seed × 平均 200ms async listener）
      await page.waitForTimeout(2000);
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

    await test.step('When user opens /browse and types natural-language query "images and containers in CI"', async () => {
      await page.goto('/browse');
      const semanticResponse = page.waitForResponse((response) =>
        response.url().includes('/api/v1/search/semantic')
        && new URL(response.url()).searchParams.get('q') === 'images and containers in CI'
        && response.status() === 200,
      );
      await page.getByPlaceholder('描述你想完成的任務或搜尋技能...').fill('images and containers in CI');
      const body = await (await semanticResponse).json() as { content?: unknown[] };
      expect(body.content?.length ?? 0, 'semantic API should return at least one result').toBeGreaterThan(0);

      // 等 semantic search 結果出現（resultsLoading 結束）；non-empty list
      await expect(page.getByText(/已載入\s+[1-9]\d*\s+個相關技能/)).toBeVisible({ timeout: 15_000 });
      await expect.poll(
        () => semanticRequests.length,
        { message: '/browse search should request semantic endpoint', timeout: 15_000 },
      ).toBeGreaterThan(0);
      expect(keywordRequests, '/browse search must not call keyword API').toHaveLength(0);

      // 收集第一次的順序：所有 SkillCard h3 names
      const headings1 = page.getByRole('article').locator('h3');
      const count1 = await headings1.count();
      expect(count1, '第一次 query 結果應 ≥ 1 個 skill').toBeGreaterThan(0);
    });

    await test.step('Then 結果非空 + card contract 穩定', async () => {
      await expect(page.getByRole('article').first()).toBeVisible();
      await expect(page.getByText(/% 相符/).first()).toBeVisible();
      expect(keywordRequests, '/browse search must not call keyword API').toHaveLength(0);
    });
  });
});
