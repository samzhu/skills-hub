// Fixtures helper — populated by playwright-expert DESIGN when an AC
// needs backed-test-API seeding (pattern 1 in references/fixtures-patterns.md).
// Copy to e2e/tests/_fixtures.ts (one per spec or shared) and adapt.
//
// Assumes the backend exposes (under a non-production profile):
//   POST {{ TEST_API_BASE }}/reset           — wipes seed-managed entities
//   POST {{ TEST_API_BASE }}/seed/{{ ENTITY }} — creates one entity, returns its id
//
// If those endpoints do not exist in the project yet, do NOT write
// this file. Emit a finding to the caller instead, per
// references/fixtures-patterns.md § Per-AC decision protocol.

import { test as base, expect, type APIRequestContext } from '@playwright/test';

const TEST_API_BASE = '{{ TEST_API_BASE }}';   // e.g. http://localhost:8080/internal/test

export type {{ ENTITY_PASCAL }}Seed = {
  // Replace with the entity's required seed fields.
  // name: string;
  // description?: string;
};

export async function reset{{ ENTITY_PASCAL }}s(req: APIRequestContext) {
  const res = await req.post(`${TEST_API_BASE}/reset`);
  expect(res.ok(), `reset failed: ${res.status()} ${await res.text()}`).toBeTruthy();
}

export async function seed{{ ENTITY_PASCAL }}(
  req: APIRequestContext,
  data: {{ ENTITY_PASCAL }}Seed,
): Promise<string> {
  const res = await req.post(`${TEST_API_BASE}/seed/{{ ENTITY }}`, { data });
  expect(res.ok(), `seed failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  return body.id as string;
}

export const test = base.extend<{ {{ ENTITY }}Reset: void }>({
  // Auto-runs before every test. Resets seed-managed entities so each
  // test starts from the same known state — pattern 1 in
  // references/fixtures-patterns.md. Tests that need a specific seed
  // call seed{{ ENTITY_PASCAL }}() explicitly inside the test body or
  // a Given step.
  {{ ENTITY }}Reset: [
    async ({ request }, use) => {
      await reset{{ ENTITY_PASCAL }}s(request);
      await use();
    },
    { auto: true },
  ],
});

export { expect };
