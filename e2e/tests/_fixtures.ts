import { test as base, expect, type APIRequestContext } from '@playwright/test';

import { readManifest as readFixtureManifest, type FixtureManifest, type FixtureSkill } from '../fixtures/manifest';
import { uploadSkillFixture } from '../fixtures/production-api-seed';

export type SkillSeed = {
  name: string;
  description: string;
  author: string;
  authorDisplayName?: string;
  authorHandle?: string;
  authorEmail?: string;
  category: string;
  version?: string;                 // default '1.0.0' on backend
  visibility?: 'PUBLIC' | 'PRIVATE'; // default PUBLIC on backend
  skillMdContent?: string;          // when null, backend synthesises minimal SKILL.md
};

export type DownloadEventSeed = {
  skillId: string;
  count: number;
  daysAgo: number;
};

export async function seedSkill(req: APIRequestContext, data: SkillSeed): Promise<string> {
  const skill = await uploadSkillFixture(req, {
    asUser: 'developer',
    ...data,
  });
  return skill.id;
}

export async function seedDownloadEvents(
  _req: APIRequestContext,
  data: DownloadEventSeed,
): Promise<number> {
  throw new Error(
    `S202-T05 pending: projection seed helper is not implemented for ${data.skillId} (${data.count} events).`,
  );
}

// Canonical fixture profiles — tag tests with `@profile-<name>` per
// playwright-expert/references/fixtures-patterns.md state taxonomy.
//
export const profiles = {
  /** No data — first-time UX, empty-state assertions. */
  async empty(_req: APIRequestContext): Promise<void> {
    await readManifest();
  },

  /** One published skill — minimal positive case (detail / download / quality score). */
  async single(_req: APIRequestContext): Promise<{ skillId: string }> {
    const manifest = await readManifest();
    expect(manifest.profiles.single?.skill.id, 'manifest profiles.single.skill.id').toBeTruthy();
    return { skillId: manifest.profiles.single!.skill.id };
  },

  /** 10 mixed skills across DevOps / Testing / Docs / DataOps — paged list, search, semantic ranking. */
  async paged(_req: APIRequestContext): Promise<{ skillIds: string[] }> {
    const manifest = await readManifest();
    const skills = manifest.profiles.paged?.skills ?? [];
    expect(skills.length, 'manifest profiles.paged.skills').toBeGreaterThanOrEqual(10);
    return { skillIds: skills.map(skill => skill.id) };
  },
};

export async function readManifest(): Promise<FixtureManifest> {
  return readFixtureManifest();
}

export async function fixtureSkill(name: string): Promise<FixtureSkill> {
  const manifest = await readManifest();
  const skill = manifest.profiles.paged?.byName[name] ?? manifest.skills[name];
  expect(skill, `fixture skill ${name} should exist in manifest`).toBeTruthy();
  return skill;
}

export const test = base;

export { expect };
