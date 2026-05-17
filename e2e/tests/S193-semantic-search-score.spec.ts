import { test, expect, profiles } from './_fixtures';

test.describe('S193 — Semantic Search Score Transparency', () => {
  test('AC-S193-5: /browse semantic card shows match percentage @S193 @ac-5 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given platform seeded with semantic-searchable public skills', async () => {
      await profiles.paged(request);
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

    await test.step('When user searches /browse with a natural-language query', async () => {
      await page.goto('/browse');
      const semanticResponse = page.waitForResponse((response) =>
        response.url().includes('/api/v1/search/semantic?q=') && response.status() === 200,
      );

      await page.getByPlaceholder('描述你想完成的任務或搜尋技能...').fill('images and containers in CI');

      const response = await semanticResponse;
      const body = await response.json() as Array<{ score?: number }>;
      expect(body.length, 'semantic API should return at least one scored result').toBeGreaterThan(0);
      expect(typeof body[0].score, 'semantic API first result should include score').toBe('number');

      const expectedMatchText = `${Math.round((body[0].score ?? 0) * 100)}% 相符`;
      await expect(page.getByText(expectedMatchText).first()).toBeVisible({ timeout: 15_000 });
    });

    await test.step('Then /browse used semantic API only and rendered a match badge', async () => {
      await expect(page.getByText(/找到/)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(/% 相符/).first()).toBeVisible();
      expect(semanticRequests.length, '/browse search should request semantic endpoint').toBeGreaterThan(0);
      expect(keywordRequests, '/browse search must not call keyword API').toHaveLength(0);
    });
  });
});
