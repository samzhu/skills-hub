// S140 critical-path E2E — AC-1 (PRD P1: Browse + keyword search).
//
// S168 升級後：E2EEmbeddingConfig stub 為 word-overlap biased（同 token →
// cosine 顯著正，無 overlap → 隨機 ±0.05）；e2e threshold=0.1。query "docker"
// 對 3 個 docker-* skill cosine ≈ 0.2 通過 threshold，其他 7 個被篩掉 →
// HomePage isSemanticMode = true → 顯示「找到 3 個相關技能」(regex 接受 keyword
// 與 semantic 兩種計數文字)。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-1: 關鍵字搜尋顯示符合條件的技能列表 @S140 @ac-1 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given platform seeded with 10 skills (3 含 docker)', async () => {
      // profiles.paged 內部先 resetAll 再 seed 10；無需額外 reset
      // （auto-fixture resetState 已先跑，這裡 seed 跟 reset 自帶順序保證）
      await profiles.paged(request);
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
      // 再確認總數 10（filter inactive 走「共 N 個技能」分支）
      await expect(page.getByText(/共\s*10\s*個技能/)).toBeVisible();
      await page.getByRole('searchbox').fill('docker');
    });

    await test.step('Then 結果列表只顯示 3 個 docker-related skill，其他 7 個 non-matching 不出現', async () => {
      // 後端 keyword search 採 case-insensitive ILIKE on name OR description (per
      // SkillQueryService.search line 174–179)；3 個 docker-* skills 全命中。
      // 計數文字兩個 mode 都接受：keyword「共 N 個技能」/ semantic「找到 N 個相關技能」。
      // HomePage `isSemanticMode = query 非空 && !semanticError && semanticResults > 0`；
      // e2e stub embedder threshold=0.0 statistically 仍有命中 → 走 semantic 分支。
      await expect(page.getByText(/3\s*個(相關)?技能/)).toBeVisible();

      // 命中項：3 張 docker-* card 的 H3 name 出現
      await expect(page.getByRole('heading', { level: 3, name: 'docker-compose-helper' })).toBeVisible();
      await expect(page.getByRole('heading', { level: 3, name: 'docker-image-builder' })).toBeVisible();
      await expect(page.getByRole('heading', { level: 3, name: 'docker-cleaner' })).toBeVisible();

      // 非命中項：抽樣 3 個 non-matching skill 不應出現（覆蓋不同 category：Testing / Lint / Docs）
      await expect(page.getByRole('heading', { level: 3, name: 'junit-test-generator' })).not.toBeVisible();
      await expect(page.getByRole('heading', { level: 3, name: 'eslint-config-pack' })).not.toBeVisible();
      await expect(page.getByRole('heading', { level: 3, name: 'docs-publisher' })).not.toBeVisible();

      // 結構欄位 spot-check：每張 SkillCard 的 author/category/risk/download 都應渲染
      // （抽 docker-compose-helper 一張驗 SkillCard 結構完整 — 不重複測 3 張免拖慢）
      const composeCard = page
        .getByRole('article')
        .filter({ has: page.getByRole('heading', { level: 3, name: 'docker-compose-helper' }) });
      await expect(composeCard.getByText('alice')).toBeVisible();             // author
      await expect(composeCard.getByText('DevOps', { exact: true })).toBeVisible();  // category pill
      await expect(composeCard.getByText('v1.0.0')).toBeVisible();            // version pill
    });
  });
});
