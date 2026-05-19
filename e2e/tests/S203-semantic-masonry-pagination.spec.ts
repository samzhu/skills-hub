import { test, expect, profiles } from './_fixtures';

type SemanticSlice = {
  content: Array<{ name: string; score?: number }>;
  last: boolean;
  number: number;
  size: number;
};

test.describe('S203 — Semantic Search Masonry Pagination', () => {
  test('AC-S203-4 / AC-S203-6: /browse scroll bottom fetches next semantic Slice without keyword fallback @S203 @ac-4 @ac-6 @happy-path @profile-paged', async ({
    page,
    request,
  }) => {
    await test.step('Given paged profile has enough semantic-searchable public skills for a second Slice', async () => {
      const { skillIds } = await profiles.paged(request);
      expect(skillIds.length, 'S203 requires more than one semantic Slice').toBeGreaterThan(10);
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

    await test.step('When user searches docker and scrolls to the semantic load-more sentinel', async () => {
      await page.goto('/browse');

      const firstPageResponse = page.waitForResponse((response) =>
        response.url().includes('/api/v1/search/semantic?q=docker')
        && response.url().includes('page=0')
        && response.url().includes('size=10')
        && response.status() === 200,
      );

      await page.getByPlaceholder('描述你想完成的任務或搜尋技能...').fill('docker');

      const firstPage = await (await firstPageResponse).json() as SemanticSlice;
      expect(firstPage.content.length, 'first semantic Slice should contain 10 cards').toBe(10);
      expect(firstPage.last, 'first semantic Slice should expose another page').toBe(false);

      const secondPageResponse = page.waitForResponse((response) =>
        response.url().includes('/api/v1/search/semantic?q=docker')
        && response.url().includes('page=1')
        && response.url().includes('size=10')
        && response.status() === 200,
      );

      await page.getByTestId('semantic-load-more-sentinel').scrollIntoViewIfNeeded();
      const secondPage = await (await secondPageResponse).json() as SemanticSlice;

      expect(secondPage.number).toBe(1);
      expect(secondPage.content.length, 'second semantic Slice should append cards').toBeGreaterThan(0);
    });

    await test.step('Then cards, match scores, and all-loaded copy are visible without keyword fallback', async () => {
      await expect(page.getByText(/已載入\s+1[1-9]\s+個相關技能/)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(/% 相符/).first()).toBeVisible();
      await expect(page.getByText('已顯示全部相關技能')).toBeVisible({ timeout: 15_000 });

      expect(semanticRequests.some(url => url.includes('page=0') && url.includes('size=10'))).toBe(true);
      expect(semanticRequests.some(url => url.includes('page=1') && url.includes('size=10'))).toBe(true);
      expect(semanticRequests.some(url => url.includes('limit='))).toBe(false);
      expect(keywordRequests, '/browse semantic pagination must not call keyword API').toHaveLength(0);
    });
  });
});
