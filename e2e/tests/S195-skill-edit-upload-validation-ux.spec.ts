import { test, expect, seedSkill } from './_fixtures';

test.describe('S195 skill edit upload validation UX', () => {
  test('AC-S195-6: mobile edit upload dropzone stays visible @S195 @ac-S195-6 @profile-single', async ({ page, request }) => {
    test.setTimeout(60_000);
    await page.setViewportSize({ width: 390, height: 844 });

    const skillId = await seedSkill(request, {
      name: 's195-edit-upload-mobile',
      description: 'Mobile evidence for edit upload dropzone.',
      author: 'lab-user',
      category: 'DevOps',
      version: '1.0.0',
      skillMdContent: `---
name: s195-edit-upload-mobile
description: Mobile evidence for edit upload dropzone.
---

# S195 Edit Upload Mobile
`,
    });

    await page.goto(`/skills/${skillId}`);
    await page.getByTestId('edit-skill-btn').click();
    await expect(page).toHaveURL(new RegExp(`/skills/${skillId}/edit$`));
    await page.getByRole('button', { name: /上傳檔案/ }).click();

    const visibleControls = [
      page.getByText('拖拽 zip 或 md 檔到此處'),
      page.getByText('或點擊選取檔案'),
      page.getByRole('button', { name: '儲存分類' }),
      page.getByRole('button', { name: '儲存新版本' }),
    ];

    for (const control of visibleControls) {
      await expect(control).toBeVisible();
      const box = await control.boundingBox();
      expect(box, 'control has a visible box').not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }
  });
});
