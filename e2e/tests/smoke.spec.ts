import { test, expect } from '@playwright/test';

// Bootstrap placeholder. Authored by playwright-expert BOOTSTRAP step 5.
// Real spec tests live alongside this file under tests/<spec-id>-*.spec.ts.

test('frontend dev server boots and serves an HTML document with a title @smoke @bootstrap', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle(/.+/);
});
