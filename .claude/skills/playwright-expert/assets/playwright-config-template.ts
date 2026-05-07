// Playwright config — populated by playwright-expert BOOTSTRAP.
// Replace markers under {{ }} after copying. Do NOT delete the markers
// inline; replace them with concrete values so that subsequent runs
// detect that bootstrap was completed.

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,

  // Reporter list. The 'github' reporter prints `::notice::` lines
  // unconditionally; only GitHub Actions parses them as annotations
  // — locally those lines are noise. Conditional spread keeps the
  // reporter active only on CI. For Jenkins / GitLab, swap or add
  // ['junit', { outputFile: 'results/junit.xml' }].
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['json', { outputFile: 'results/report.json' }],
    ...(process.env.CI ? [['github'] as ['github']] : []),
  ],

  // Artefact policy. Officially documented default for CI is only
  // trace: 'on-first-retry' (see playwright.dev/docs/ci-intro). The
  // screenshot and video values below are community convention —
  // adjust per spec / per debug session.
  use: {
    baseURL: '{{ FRONTEND_URL }}',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: [
    {
      name: 'Backend',
      command: '{{ BACKEND_COMMAND }}',
      cwd: '{{ BACKEND_CWD }}',
      url: '{{ BACKEND_HEALTH_URL }}',
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      name: 'Frontend',
      command: '{{ FRONTEND_COMMAND }}',
      cwd: '{{ FRONTEND_CWD }}',
      url: '{{ FRONTEND_URL }}',
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
