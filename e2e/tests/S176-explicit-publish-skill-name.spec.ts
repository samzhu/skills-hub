import type { Page } from '@playwright/test';
import { test, expect, profiles } from './_fixtures';

type SkillsPage = {
  content: Array<{ id: string; name: string }>;
};

async function publishTextSkill(page: Page, packageName: string): Promise<string> {
  await page.goto('/publish');
  await expect(page.getByRole('heading', { level: 1, name: '發佈新技能' })).toBeVisible();

  await page.getByLabel('技能名稱').fill('transcribe-video');
  await page.getByRole('button', { name: '貼上文本' }).click();
  await page.getByPlaceholder(/name: my-skill/).fill(`---
name: ${packageName}
description: S176 E2E package metadata for ${packageName}.
version: 1.0.0
license: MIT
---

# ${packageName}

Use this skill for S176 duplicate platform-name browser verification.
`);
  await page.getByPlaceholder('DevOps').fill('Testing');
  await page.getByRole('button', { name: '發佈技能' }).click();

  await page.waitForURL(/\/publish\/(validate|review)\?id=/, { timeout: 30_000 });
  const firstUrl = new URL(page.url());
  const id = firstUrl.searchParams.get('id');
  expect(id, `publish redirect missing id: ${page.url()}`).toBeTruthy();

  await page.waitForURL(/\/publish\/review\?id=/, { timeout: 30_000 });
  await expect(page.getByText(/上[傳架]成功|已上架|上架/).first()).toBeVisible();
  return id!;
}

test.describe('S176 — Explicit Publish Skill Name', () => {
  test('AC-S176-1/2/3: publish duplicate platform skill names through browser @S176 @ac-1 @ac-2 @ac-3', async ({
    page,
    request,
  }) => {
    await test.step('Given empty platform state + authenticated lab user', async () => {
      await profiles.empty(request);
    });

    let firstId = '';
    let secondId = '';

    await test.step('When publishing two packages with the same platform skill name', async () => {
      firstId = await publishTextSkill(page, 'internal-package-one');
      secondId = await publishTextSkill(page, 'internal-package-two');
    });

    await test.step('Then both publishes succeeded with different ids and list API shows two same-name skills', async () => {
      expect(secondId).not.toBe(firstId);

      const res = await request.get('http://localhost:8080/api/v1/skills?keyword=transcribe-video&page=0&size=20');
      expect(res.ok(), `skills query failed: ${res.status()} ${await res.text()}`).toBeTruthy();
      const body = await res.json() as SkillsPage;
      const duplicates = body.content.filter(skill => skill.name === 'transcribe-video');

      expect(duplicates.map(skill => skill.id).sort()).toEqual([firstId, secondId].sort());
    });
  });
});
