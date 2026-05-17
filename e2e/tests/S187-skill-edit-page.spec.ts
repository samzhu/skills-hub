import { test, expect, seedSkill } from './_fixtures';

const UPDATED_SKILL_MD = `---
name: s187-edit-flow
description: Updated description for S187 browser validation
---

# S187 Edit Flow

Use when verifying the edit page version publish path.
`;

test.describe('S187 skill edit page', () => {
  test('AC-S187-8/10: mobile edit controls and version validation redirect @S187 @ac-S187-8 @ac-S187-10 @profile-single', async ({ page, request }) => {
    test.setTimeout(60_000);
    await page.setViewportSize({ width: 390, height: 844 });
    const skillId = await seedSkill(request, {
      name: 's187-edit-flow',
      description: 'Initial description for S187 edit flow.',
      author: 'lab-user',
      category: 'DevOps',
      version: '1.0.0',
      skillMdContent: `---
name: s187-edit-flow
description: Initial description for S187 edit flow.
---

# S187 Edit Flow
`,
    });

    await page.goto(`/skills/${skillId}`);
    await page.getByTestId('edit-skill-btn').click();
    await expect(page).toHaveURL(new RegExp(`/skills/${skillId}/edit$`));

    const visibleControls = [
      page.getByRole('link', { name: '取消' }),
      page.getByRole('button', { name: '儲存分類' }),
      page.getByRole('button', { name: '儲存新版本' }),
      page.getByLabel('分類'),
      page.getByLabel('版本號'),
      page.getByLabel('SKILL.md 內容'),
    ];
    for (const control of visibleControls) {
      await expect(control).toBeVisible();
      const box = await control.boundingBox();
      expect(box, 'control has a visible box').not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }

    await page.getByLabel('版本號').fill('1.1.0');
    await page.getByLabel('SKILL.md 內容').fill(UPDATED_SKILL_MD);
    await page.getByRole('button', { name: '儲存新版本' }).click();

    await page.waitForURL(new RegExp(`/publish/validate\\?id=${skillId}&mode=version`), { timeout: 30_000 });
    await expect(page.getByRole('heading', { name: '新版本驗證中' })).toBeVisible();
    await expect(page.getByText(/風險掃描進行中/)).toBeVisible();

    await page.waitForURL(new RegExp(`/skills/${skillId}$`), { timeout: 45_000 });
    await expect(page.getByText('s187-edit-flow').first()).toBeVisible();
    await expect(page.getByText('v1.1.0')).toBeVisible();
    await expect(page.getByText('Updated description for S187 browser validation').first()).toBeVisible();
  });
});
