// S140 critical-path E2E — AC-2 (PRD P1 detail + S135b Quality Score).
//
// `profiles.single` seeds 1 skill via SkillCommandService.uploadSkill →
// 完整 aggregate path 含 v1.0.0 publish。Quality Score 是 async judge 的 read-side
// 結果；S202 fixture runner 以 guarded projection seed 寫固定 score row，讓
// browser E2E 看 production API/UI 呈現，不把 test judge bean 放進 production app。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-2: Skill 詳情頁顯示完整 SKILL.md + 版本 + Quality Score @S140 @ac-2 @happy-path @profile-single', async ({
    page,
    request,
  }) => {
    let skillId = '';

    await test.step('Given platform seeded with 1 skill (含 SKILL.md + v1.0.0)', async () => {
      const seeded = await profiles.single(request);
      skillId = seeded.skillId;
      expect(skillId).toMatch(/^[0-9a-f-]{36}$/i);  // UUID shape
    });

    await test.step('When user navigates to skill detail page', async () => {
      await page.goto(`/skills/${skillId}`);
    });

    await test.step('Then 詳情頁渲染：name H1 + author + 版本 v1.0.0 + 風險 badge + 下載次數 + Quality 區塊', async () => {
      // Hero: H1 skill name + production OAuth author label + version pill
      // S192 後一般 UI 必須顯示人類可讀名稱，不能把 raw author id 當 label。
      await expect(page.getByRole('heading', { level: 1, name: 'docker-compose-helper' })).toBeVisible();
      await expect(page.getByText('作者：Dev-042')).toBeVisible();
      await expect(page.getByText('v1.0.0', { exact: true })).toBeVisible();

      // 4 MetricCard labels — 下載次數 / 評分 / 版本數 / (其他) per PageHeader.tsx S140 後 UI rework
      await expect(page.getByText('下載次數', { exact: true })).toBeVisible();
      await expect(page.getByText('版本數', { exact: true })).toBeVisible();

      // 概要 tab — 描述（對應 SKILL.md description frontmatter 同義 surface；
      // seed 用 description 合成 SKILL.md，故描述即為 markdown source 來源）
      // UI rework 後描述出現 3 次（page-header / frontmatter-syntax / skill-md-tab）→ 用 page-header 範圍鎖定
      await expect(
        page.getByTestId('page-header').getByText('Helper skill for orchestrating docker-compose dev stacks.'),
      ).toBeVisible();

      await expect(page.getByRole('button', { name: /品質分析/ }).getByText('92%')).toBeVisible();
      await expect(page.getByRole('tab', { name: '品質' })).toBeVisible();

      // 版本 tab — UI rework 後 tab name 從「版本歷史」→「版本」；click 後顯示 v1.0.0 version row
      await page.getByRole('tab', { name: '版本', exact: true }).click();
      // VersionList 內含 v1.0.0；多 visible occurrence（hero pill + version row）→ 用 first
      await expect(page.getByText('v1.0.0').first()).toBeVisible();
    });
  });
});
