import { type APIRequestContext, expect } from '@playwright/test';
import { Buffer } from 'node:buffer';

export type SeedRole = 'developer' | 'viewer' | 'admin';

export type SkillSeed = {
  name: string;
  description: string;
  author?: string;
  authorDisplayName?: string;
  authorHandle?: string;
  authorEmail?: string;
  category: string;
  version?: string;
  visibility?: 'PUBLIC' | 'PRIVATE';
  skillMdContent?: string;
  asUser?: SeedRole;
};

export type UploadedFixtureSkill = {
  id: string;
  name: string;
  description: string;
  version: string;
  category: string;
  profile: string;
  detailPath: string;
};

const CLIENT_IDS: Record<SeedRole, string> = {
  admin: 'admin-client',
  developer: 'developer-client',
  viewer: 'viewer-client',
};

export async function tokenFor(
  request: APIRequestContext,
  role: SeedRole,
  oauthBaseUrl = process.env.SKILLSHUB_E2E_OAUTH_BASE_URL ?? 'http://localhost:9000/skills-hub-dev',
): Promise<string> {
  const response = await request.post(`${oauthBaseUrl}/token`, {
    headers: {
      // mock-oauth2-server uses request host when building issuer URLs. Keep the token issuer
      // aligned with the app container's issuer-uri in compose.e2e.yaml.
      Host: process.env.SKILLSHUB_E2E_OAUTH_INTERNAL_HOST ?? 'mock-oauth2-server:8080',
    },
    form: {
      grant_type: 'client_credentials',
      client_id: CLIENT_IDS[role],
      client_secret: 'secret',
      scope: 'skills:read skills:write',
    },
  });
  expect(response.ok(), `token request for ${role} failed: ${response.status()} ${await response.text()}`).toBeTruthy();
  const body = await response.json() as { access_token?: string };
  expect(body.access_token, `token response for ${role} should include access_token`).toBeTruthy();
  return body.access_token!;
}

export async function uploadSkillFixture(
  request: APIRequestContext,
  input: SkillSeed,
): Promise<UploadedFixtureSkill> {
  const version = input.version ?? '1.0.0';
  const token = await tokenFor(request, input.asUser ?? 'developer');
  const skillMd = input.skillMdContent ?? defaultSkillMd(input.name, input.description);
  const zipBuffer = createMinimalSkillZip(skillMd);
  const response = await request.post('/api/v1/skills/upload', {
    headers: {
      Authorization: `Bearer ${token}`,
    },
    multipart: {
      file: {
        name: `${input.name}.zip`,
        mimeType: 'application/zip',
        buffer: zipBuffer,
      },
      skillName: input.name,
      version,
      category: input.category,
      visibility: input.visibility ?? 'PUBLIC',
    },
  });

  expect(
    response.ok(),
    `POST /api/v1/skills/upload failed for ${input.name}: ${response.status()} ${await response.text()}`,
  ).toBeTruthy();
  const body = await response.json() as { id?: string };
  expect(body.id, `upload response for ${input.name} should include id`).toMatch(/^[0-9a-f-]{36}$/i);
  return {
    id: body.id!,
    name: input.name,
    description: input.description,
    version,
    category: input.category,
    profile: input.asUser ?? 'developer',
    detailPath: `/skills/${body.id}`,
  };
}

export function defaultSkillMd(name: string, description: string): string {
  return `---
name: ${name}
description: ${description}
version: 1.0.0
license: MIT
---

# ${name}

${description}
`;
}

export function createMinimalSkillZip(skillMdContent: string): Buffer {
  return createZip([{ name: 'SKILL.md', content: Buffer.from(skillMdContent, 'utf8') }]);
}

function createZip(entries: Array<{ name: string; content: Buffer }>): Buffer {
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;

  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const crc = crc32(entry.content);
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(0, 8);
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(entry.content.length, 18);
    localHeader.writeUInt32LE(entry.content.length, 22);
    localHeader.writeUInt16LE(name.length, 26);
    localHeader.writeUInt16LE(0, 28);
    localParts.push(localHeader, name, entry.content);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(0, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0, 14);
    centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(entry.content.length, 20);
    centralHeader.writeUInt32LE(entry.content.length, 24);
    centralHeader.writeUInt16LE(name.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    centralParts.push(centralHeader, name);

    offset += localHeader.length + name.length + entry.content.length;
  }

  const centralStart = offset;
  const central = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(centralStart, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, central, end]);
}

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});
