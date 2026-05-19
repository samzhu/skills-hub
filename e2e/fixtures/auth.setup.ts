import { expect, test as setup, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

type AuthRole = 'developer' | 'viewer' | 'admin';

type ExpectedClaims = {
  registrationId: string;
  clientId: string;
  sub: string;
  email: string;
  name: string;
  picture: string;
  storageState: string;
};

const AUTH_DIR = 'playwright/.auth';
const savedRoles = new Set<AuthRole>();

const CLAIMS: Record<AuthRole, ExpectedClaims> = {
  developer: {
    registrationId: 'skillshub',
    clientId: 'developer-client',
    sub: 'dev-042',
    email: 'developer@example.test',
    name: 'Dev-042',
    picture: 'https://example.test/avatar/developer.png',
    storageState: `${AUTH_DIR}/developer.json`,
  },
  viewer: {
    registrationId: 'skillshubviewer',
    clientId: 'viewer-client',
    sub: 'viewer-007',
    email: 'viewer@example.test',
    name: 'Viewer User',
    picture: 'https://example.test/avatar/viewer.png',
    storageState: `${AUTH_DIR}/viewer.json`,
  },
  admin: {
    registrationId: 'skillshubadmin',
    clientId: 'admin-client',
    sub: 'admin-001',
    email: 'admin@example.test',
    name: 'Admin User',
    picture: 'https://example.test/avatar/admin.png',
    storageState: `${AUTH_DIR}/admin.json`,
  },
};

setup('@S202 @AC-S202-9: anonymous header shows login', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('button', { name: '登入' })).toBeVisible();
});

for (const role of ['developer', 'viewer', 'admin'] as const) {
  setup(`@S202 @AC-S202-9: ${role} login stores browser session and /me returns OIDC claims`, async ({ browser }) => {
    await mkdir(AUTH_DIR, { recursive: true });
    await loginAndStoreState(browser, role);
  });
}

async function loginAndStoreState(browser: Browser, role: AuthRole): Promise<void> {
  const expected = CLAIMS[role];
  const context = await browser.newContext({ storageState: undefined });
  const page = await context.newPage();

  try {
    await page.goto(`/oauth2/authorization/${expected.registrationId}?returnTo=/`);
    await completeMockLoginIfPrompted(page, expected);
    await page.waitForURL('http://localhost:8080/', { timeout: 20_000 });

    const me = await page.request.get('/api/v1/me');
    if (!me.ok()) {
      throw new Error(`mock OAuth login issuer mismatch: GET /api/v1/me returned ${me.status()} ${await me.text()}`);
    }
    const body = await me.json() as Record<string, unknown>;
    expect(body.sub, `${role} sub`).toBe(expected.sub);
    expect(body.email, `${role} email`).toBe(expected.email);
    expect(body.name, `${role} name`).toBe(expected.name);
    expect(body.picture, `${role} picture`).toBe(expected.picture);

    await page.reload();
    await expect(page.getByRole('button', { name: '開啟使用者選單' })).toBeVisible({ timeout: 20_000 });
    await context.storageState({ path: expected.storageState });
    savedRoles.add(role);
    console.log(`S202 T06 ${role} storageState saved: ${expected.storageState}`);
  } catch (error) {
    throw new Error(`mock OAuth login issuer mismatch: ${error instanceof Error ? error.message : String(error)}`);
  } finally {
    await context.close();
  }
}

async function completeMockLoginIfPrompted(page: Page, expected: ExpectedClaims): Promise<void> {
  const subjectInput = page.locator('input[name="username"]').first();
  if (await subjectInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await subjectInput.fill(expected.sub);
    await page.locator('textarea').first().fill(JSON.stringify({
      email: expected.email,
      name: expected.name,
      picture: expected.picture,
      aud: [expected.clientId],
    }));
    await page.locator('input[type="submit"]').click();
    return;
  }

  const linkLoginHeader = page.getByRole('heading', { name: 'Login with OAuth 2.0' });
  if (await linkLoginHeader.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await page.getByRole('link', { name: expected.registrationId }).click();
    await page.locator('input[name="username"]').first().fill(expected.sub);
    await page.locator('textarea').first().fill(JSON.stringify({
      email: expected.email,
      name: expected.name,
      picture: expected.picture,
      aud: [expected.clientId],
    }));
    await page.locator('input[type="submit"]').click();
  }
}

setup.afterAll(() => {
  expect(savedRoles, 'S202-T06 saved auth roles').toEqual(new Set<AuthRole>(['developer', 'viewer', 'admin']));
  console.log('S202 T06 POC PASS');
});
