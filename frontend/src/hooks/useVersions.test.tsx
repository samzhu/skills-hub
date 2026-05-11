import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useVersions } from './useVersions'

/**
 * useVersions hook tests — `enabled` guard 防止 /skills//versions 空 id 請求。
 */

const wrapper = ({ children }: { children: ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve([{ id: 'v1', skillId: 'skill-1', version: '1.0.0', fileSize: 1024, publishedAt: '2026-04-01T00:00:00Z' }]),
  } as Response)
})

describe('useVersions', () => {
  it('AC-1: empty skillId → query disabled (no fetch)', async () => {
    renderHook(() => useVersions(''), { wrapper })
    await new Promise((r) => setTimeout(r, 0))
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('AC-2: valid skillId → fetch invoked + data resolved', async () => {
    const { result } = renderHook(() => useVersions('skill-1'), { wrapper })
    await waitFor(() => {
      expect(result.current.data?.length).toBe(1)
    })
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })

  it('AC-3: query key includes skillId for cache isolation', async () => {
    const { result: r1 } = renderHook(() => useVersions('skill-1'), { wrapper })
    const { result: r2 } = renderHook(() => useVersions('skill-2'), { wrapper })
    await waitFor(() => expect(r1.current.data).toBeDefined())
    await waitFor(() => expect(r2.current.data).toBeDefined())
    // 兩個 distinct skillId 各自 cache key 觸發 2 次 fetch
    expect(globalThis.fetch).toHaveBeenCalledTimes(2)
  })
})
