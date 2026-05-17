import { describe, expect, it, vi } from 'vitest'
import { addVersion, uploadSkill } from './skills'

describe('skills API — S188 optional version FormData', () => {
  it('AC-S188-5: uploadSkill omits blank version from FormData', async () => {
    let capturedForm: FormData | null = null
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => {
      capturedForm = init?.body as FormData
      return {
        ok: true,
        status: 200,
        json: async () => ({ id: 'sk_s188' }),
      } as Response
    }))

    await uploadSkill(
      new File(['skill'], 'SKILL.md', { type: 'text/markdown' }),
      'optional-version-skill',
      '   ',
      'DevOps',
      'PUBLIC',
    )

    expect(capturedForm).not.toBeNull()
    expect(capturedForm!.get('skillName')).toBe('optional-version-skill')
    expect(capturedForm!.get('version')).toBeNull()
  })

  it('AC-S188-6: addVersion omits blank version from FormData', async () => {
    let capturedForm: FormData | null = null
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => {
      capturedForm = init?.body as FormData
      return {
        ok: true,
        status: 204,
        json: async () => ({}),
      } as Response
    }))

    await addVersion(
      'sk_s188',
      new File(['zip'], 'skill.zip', { type: 'application/zip' }),
      '',
    )

    expect(capturedForm).not.toBeNull()
    expect(capturedForm!.get('file')).toBeInstanceOf(File)
    expect(capturedForm!.get('version')).toBeNull()
  })
})
