import { describe, expect, it, vi, beforeEach } from 'vitest'
import { revokeGrant } from './grants'

describe('grants API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('轉為私人 revoke public grant：202 空回應也算成功', async () => {
    const json = vi.fn()
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json,
    } as unknown as Response)

    await expect(revokeGrant('skill-1', 'public-grant-1')).resolves.toBeUndefined()

    expect(globalThis.fetch).toHaveBeenCalledWith(
      '/api/v1/skills/skill-1/grants/public-grant-1',
      { method: 'DELETE' },
    )
    expect(json).not.toHaveBeenCalled()
  })
})
