// S140 critical-path E2E — AC-6 (PRD P6: Analytics dashboard).
//
// AnalyticsPage 抓 GET /api/v1/analytics/overview → useOverview hook 回
// { totalSkills, totalDownloads, newSkillsThisWeek, topSkills[] }。Backend
// 由 AnalyticsProjection 訂閱 SkillDownloadedEvent 累計 download_events row。
// S202 後 setup fixtures 會先建立 paged baseline。本 AC 只補
// docker-compose-helper 的 read-side download projection，確認 analytics UI 看得到 5。

import { test, expect, profiles, readManifest, seedDownloadEvents } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-6: Analytics dashboard 顯示總覽 + 熱門排行 @S140 @ac-6 @happy-path @profile-single', async ({
    page,
    request,
  }) => {
    let skillId = '';
    let expectedTotalSkills = 0;

    await test.step('Given platform seeded with baseline skills + 5 download events', async () => {
      const seeded = await profiles.single(request);
      skillId = seeded.skillId;
      expectedTotalSkills = await readBaselineSkillCount();
      const inserted = await seedDownloadEvents(request, { skillId, count: 5, daysAgo: 7 });
      expect(inserted).toBe(5);
      // Backend 直 INSERT download_events，無 async listener，不需額外 buffer
    });

    await test.step('When user opens /analytics', async () => {
      await page.goto('/analytics');
      await expect(page.getByRole('heading', { level: 1, name: '平台數據分析' })).toBeVisible({ timeout: 10_000 });
    });

    await test.step('Then 總下載次數=5，熱門 Top 10 含 seeded skill', async () => {
      // 4 metric cards — label + value pair（MetricCard 結構：<dt>label</dt><dd>value</dd>）
      // 用 filter 把 metric label 與其 value cell 配對，避免「總技能數 1」與「Top 1」混淆
      const totalSkillsCard = page.getByText('總技能數').locator('..');
      await expect(totalSkillsCard.getByText(new RegExp(`^\\s*${expectedTotalSkills}\\s*$`))).toBeVisible();

      const totalDownloadsCard = page.getByText('總下載次數').locator('..');
      await expect(totalDownloadsCard.getByText(/^\s*5\s*$/)).toBeVisible();

      // 熱門技能 — UI rework 後 heading 從 "Top 10" → "前 10 名"，rank label 改為純數字 "1"
      await expect(page.getByRole('heading', { level: 2, name: '熱門技能 前 10 名' })).toBeVisible();
      await expect(page.getByText('docker-compose-helper')).toBeVisible();
      // download count 5 displayed in row（tabular-nums span）
      await expect(page.locator('text=docker-compose-helper').locator('..').getByText(/^\s*5\s*$/)).toBeVisible();
    });
  });
});

async function readBaselineSkillCount(): Promise<number> {
  const manifest = await readManifest();
  return manifest.profiles.paged?.skills.length ?? 0;
}
