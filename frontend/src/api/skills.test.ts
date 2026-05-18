import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { addVersion, uploadSkill } from './skills'
import { localizeApiError } from '@/lib/api-error-messages'

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

  it('AC-S195-4: addVersion preserves response findings on validation error', async () => {
    const findings = [
      {
        section: 'skill_md',
        severity: 'error' as const,
        title: "metadata: key 'owner' nested object is not supported",
        hint: null,
      },
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        error: 'VALIDATION_ERROR',
        message: 'SKILL.md validation failed',
        findings,
      }),
    } as Response))

    let thrown: unknown
    try {
      await addVersion(
        'skill-docker',
        new File(['zip'], 'skill.zip', { type: 'application/zip' }),
        '2',
      )
    } catch (err) {
      thrown = err
    }

    expect(ApiError.is(thrown)).toBe(true)
    expect(thrown).toMatchObject({
      name: 'ApiError',
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'SKILL.md validation failed',
      findings,
    })
    expect(localizeApiError(thrown)).toBe('驗證失敗：SKILL.md validation failed')
    expect((thrown as ApiError).findings?.[0]?.title).toBe("metadata: key 'owner' nested object is not supported")
  })

  it('AC-S195-4: addVersion keeps fallback message when error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => { throw new Error('not json') },
    } as unknown as Response))

    await expect(addVersion(
      'skill-docker',
      new File(['zip'], 'skill.zip', { type: 'application/zip' }),
      '2',
    )).rejects.toMatchObject({
      name: 'ApiError',
      status: 503,
      message: 'Version upload failed: 503',
    })
  })

  it('AC-S199-1: uploadSkill preserves response findings on validation error', async () => {
    const findings = [
      {
        section: 'skill_md',
        severity: 'error' as const,
        title: 'skill_md_line_count: SKILL.md has 589 lines (max 500)',
        hint: null,
      },
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        error: 'VALIDATION_ERROR',
        message: 'SKILL.md validation failed',
        findings,
      }),
    } as Response))

    let thrown: unknown
    try {
      await uploadSkill(
        new File(['skill'], 'SKILL.md', { type: 'text/markdown' }),
        'Team Skill',
        '',
        'DevOps',
      )
    } catch (err) {
      thrown = err
    }

    expect(ApiError.is(thrown)).toBe(true)
    expect(thrown).toMatchObject({
      name: 'ApiError',
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'SKILL.md validation failed',
      findings,
    })
    expect(localizeApiError(thrown)).toBe('驗證失敗：SKILL.md validation failed')
    expect((thrown as ApiError).findings?.[0]?.title).toBe('skill_md_line_count: SKILL.md has 589 lines (max 500)')
  })

  it('AC-S199-1: uploadSkill keeps fallback when validation body has no findings', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        error: 'VALIDATION_ERROR',
        message: 'SKILL.md validation failed',
      }),
    } as Response))

    await expect(uploadSkill(
      new File(['skill'], 'SKILL.md', { type: 'text/markdown' }),
      'Team Skill',
      undefined,
      'DevOps',
    )).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'SKILL.md validation failed',
      findings: undefined,
    })
  })
})
