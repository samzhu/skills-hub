import { test, expect, profiles, seedSkill } from './_fixtures';
import type { Page } from '@playwright/test';

const viewports = [
  { width: 390, height: 844 },
  { width: 768, height: 900 },
  { width: 900, height: 700 },
  { width: 1440, height: 900 },
] as const;

async function expectNoBodyOverflow(page: Page, label: string) {
  const dims = await page.evaluate(() => {
    const clientWidth = document.documentElement.clientWidth;
    const offenders = Array.from(document.body.querySelectorAll<HTMLElement>('*'))
      .map((el) => {
        const rect = el.getBoundingClientRect();
        return {
          tag: el.tagName.toLowerCase(),
          text: (el.textContent ?? '').trim().slice(0, 80),
          className: typeof el.className === 'string' ? el.className : '',
          right: Math.round(rect.right),
          width: Math.round(rect.width),
        };
      })
      .filter((item) => item.right > clientWidth + 1)
      .sort((a, b) => b.right - a.right)
      .slice(0, 5);

    return {
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth,
      offenders,
    };
  });
  expect(
    dims.scrollWidth,
    `${label} overflow: scrollWidth=${dims.scrollWidth}, clientWidth=${dims.clientWidth}, offenders=${JSON.stringify(dims.offenders)}`,
  ).toBeLessThanOrEqual(dims.clientWidth + 1);
}

async function waitForRouteReady(page: Page, route: string, skillId: string) {
  if (route === '/') {
    await expect(page.getByRole('heading', { name: /你的團隊真的可以/ })).toBeVisible();
  } else if (route === '/browse') {
    await expect(page.getByRole('searchbox')).toBeVisible();
  } else if (route === '/collections') {
    await expect(page.getByRole('heading', { name: '精選技能集合' })).toBeVisible();
  } else if (route === '/my-skills') {
    await expect(page.getByRole('heading', { name: /你的 .* 個技能/ })).toBeVisible();
  } else if (route === '/publish') {
    await expect(page.getByRole('heading', { name: '發佈新技能' })).toBeVisible();
  } else if (route === '/docs/overview') {
    await expect(page.getByRole('heading', { name: 'Skills Hub 概覽' })).toBeVisible();
  } else if (route === `/skills/${skillId}`) {
    await expect(page.getByRole('heading', { name: 'responsive-polish-helper' })).toBeVisible();
  }
}

test.describe('S172 — responsive polish guard', () => {
  test('AC-S172-16: audited routes have no body horizontal overflow @S172 @responsive-polish @happy-path', async ({
    page,
    request,
  }) => {
    const skillId = await seedSkill(request, {
      name: 'responsive-polish-helper',
      description: 'S172 responsive polish fixture.',
      author: 'lab-user',
      category: 'DevOps',
      version: '1.0.0',
    });
    const routes = ['/', '/browse', '/collections', '/my-skills', '/publish', '/docs/overview', `/skills/${skillId}`];

    for (const viewport of viewports) {
      await page.setViewportSize(viewport);
      for (const route of routes) {
        await page.goto(route);
        await waitForRouteReady(page, route, skillId);
        await expectNoBodyOverflow(page, `${route} @ ${viewport.width}x${viewport.height}`);
      }
    }
  });

  test('AC-S172-16: browse empty suggestions and collection dialog expose real controls @S172 @responsive-polish @happy-path', async ({
    page,
    request,
  }) => {
    await profiles.empty(request);

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/browse');
    await page.getByRole('searchbox').fill('s172-no-result-query');
    await expect(page.getByRole('heading', { name: '找不到符合的技能' })).toBeVisible();
    await expect(page.getByRole('button', { name: /清除關鍵字並瀏覽全部技能/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /發布你自己的技能/ })).toHaveAttribute('href', '/publish');
    await expect(page.getByText('切換到語意搜尋模式')).toHaveCount(0);
    await expectNoBodyOverflow(page, '/browse empty @ 390x844');

    const skillId = await seedSkill(request, {
      name: 'collection-picker-helper',
      description: 'S172 collection modal fixture.',
      author: 'lab-user',
      category: 'DevOps',
      version: '1.0.0',
    });

    await page.goto('/collections');
    await page.getByRole('button', { name: '建立集合' }).click();
    await expect(page.getByRole('dialog', { name: '建立集合' })).toBeVisible();
    await expect(page.getByLabel('新增技能')).toBeVisible();
    await expect(page.getByTestId('selected-skills-list')).toBeVisible();
    await expect(page.getByLabel(/技能 ID 清單/)).toHaveCount(0);

    await page.getByLabel('新增技能').selectOption(skillId);
    await page.getByRole('button', { name: '新增' }).click();
    await expect(page.getByTestId('selected-skills-list').getByText('collection-picker-helper')).toBeVisible();
    await expectNoBodyOverflow(page, '/collections dialog @ 390x844');
  });

  test('AC-S172-16: my-skills lifecycle tabs are dark at mobile width @S172 @responsive-polish @happy-path', async ({
    page,
    request,
  }) => {
    await seedSkill(request, {
      name: 'my-skills-tab-helper',
      description: 'S172 my-skills tab fixture.',
      author: 'lab-user',
      category: 'Testing',
      version: '1.0.0',
    });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/my-skills');
    await expect(page.getByTestId('my-skills-lifecycle-tabs')).toBeVisible();

    const tabs = page.getByTestId('my-skills-lifecycle-tabs').getByRole('button');
    await expect(tabs).toHaveCount(5);
    const tabStyles = await tabs.evaluateAll((buttons) =>
      buttons.map((button) => ({
        className: button.className,
        backgroundColor: getComputedStyle(button).backgroundColor,
      })),
    );

    for (const style of tabStyles) {
      expect(style.className).not.toContain('bg-white');
      expect(style.backgroundColor).not.toBe('rgb(255, 255, 255)');
    }
    await expectNoBodyOverflow(page, '/my-skills tabs @ 390x844');
  });
});
