import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useSkill, useSkillByAuthorAndName } from './useSkill'

/**
 * useSkill hook tests — `enabled` guard 與 fetcher invocation 行為。
 * Per ADR-003 dual-route：useSkill(id) vs useSkillByAuthorAndName(author, name)
 * 各自 cache key 不衝突。
 */

const wrapper = ({ children }: { children: ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.clearAllMocks()
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ id: 'skill-1', name: 'mock' }),
  } as Response)
})

describe('useSkill', () => {
  it('AC-1: empty id → query disabled (no fetch)', async () => {
    renderHook(() => useSkill(''), { wrapper })
    // give event loop time to flush
    await new Promise((r) => setTimeout(r, 0))
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('AC-2: valid id → query enabled, fetch invoked', async () => {
    const { result } = renderHook(() => useSkill('skill-1'), { wrapper })
    await waitFor(() => {
      expect(result.current.data).toEqual({ id: 'skill-1', name: 'mock' })
    })
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })
})

describe('useSkillByAuthorAndName — S096c canonical route', () => {
  it('AC-1: missing author → query disabled', async () => {
    renderHook(() => useSkillByAuthorAndName(undefined, 'date-formatter'), { wrapper })
    await new Promise((r) => setTimeout(r, 0))
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('AC-2: missing name → query disabled', async () => {
    renderHook(() => useSkillByAuthorAndName('team-a', undefined), { wrapper })
    await new Promise((r) => setTimeout(r, 0))
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('AC-3: both present → query enabled, fetch invoked', async () => {
    const { result } = renderHook(
      () => useSkillByAuthorAndName('team-a', 'date-formatter'),
      { wrapper },
    )
    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })
})
