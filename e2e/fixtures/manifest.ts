import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export type FixtureSkill = {
  id: string;
  name: string;
  version: string;
  profile: string;
};

export type FixtureManifest = {
  runId: string;
  baseUrl: string;
  createdAt: string;
  profiles: {
    empty: {
      skillIds: string[];
    };
  };
  skills: Record<string, FixtureSkill>;
};

export const MANIFEST_PATH = fileURLToPath(new URL('../results/fixtures.json', import.meta.url));

export async function readManifest(path = MANIFEST_PATH): Promise<FixtureManifest> {
  return JSON.parse(await readFile(path, 'utf8')) as FixtureManifest;
}

export async function writeManifest(manifest: FixtureManifest, path = MANIFEST_PATH): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}
