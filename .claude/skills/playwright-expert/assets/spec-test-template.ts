// AC-based Playwright spec — populated by playwright-expert DESIGN.
// One file per spec id. Each AC becomes one test() block with three
// test.step() calls mapping to Given / When / Then.
//
// File name convention: tests/<spec-id>-<short-slug>.spec.ts
// Test name convention: test('AC-N: <verbatim AC headline>', ...)
// Tag convention:        @<spec-id> @ac-N @happy-path|@edge

import { test, expect } from '@playwright/test';

test.describe('{{ SPEC_ID }} — {{ SPEC_TITLE }}', () => {
  test('AC-{{ AC_NUMBER }}: {{ AC_HEADLINE }} @{{ SPEC_ID }} @ac-{{ AC_NUMBER }} @happy-path', async ({ page }) => {
    await test.step('Given {{ GIVEN }}', async () => {
      await page.goto('{{ START_PATH }}');
    });

    await test.step('When {{ WHEN }}', async () => {
      // Replace with concrete locator/action.
      // Prefer getByRole / getByLabel / getByTestId over CSS selectors.
      // await page.getByRole('button', { name: '{{ ACTION_LABEL }}' }).click();
    });

    await test.step('Then {{ THEN }}', async () => {
      // Replace with concrete assertion.
      // await expect(page.getByRole('heading', { name: '{{ EXPECTED }}' })).toBeVisible();
    });
  });
});
