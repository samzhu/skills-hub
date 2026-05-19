import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import { uploadSkillFixture } from './production-api-seed';

test('@S202 @AC-S202-4: uploads skill through production API with developer token', async ({ request }) => {
  const skill = await uploadSkillFixture(request, {
    asUser: 'developer',
    name: `s202-production-api-${Date.now()}`,
    description: 'S202 production API seed helper fixture.',
    author: 'mallory-overridden',
    category: 'DevOps',
    version: '1.0.0',
  });

  expect(skill.id).toMatch(/^[0-9a-f-]{36}$/i);
  expect(skill.detailPath).toBe(`/skills/${skill.id}`);
});

test('@S202 @AC-S202-5: seed helper never calls forbidden test route', async () => {
  const sourcePath = fileURLToPath(new URL('./production-api-seed.ts', import.meta.url));
  const source = await readFile(sourcePath, 'utf8');
  const forbiddenPath = ['/internal', 'test'].join('/');
  expect(source).not.toContain(forbiddenPath);
});
