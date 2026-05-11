import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useCategories } from './useCategories'

/**
 * useCategories hook tests — fetch + cache key behavior。
 * 第 3 個（最後）hook test，完成 useSkill / useVersions / useCategories 全 hook coverage。
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
    json: () => Promise.resolve([
      { name: 'devops', count: 12 },
      { name: 'security', count: 5 },
    ]),
  } as Response)
})

describe('useCategories', () => {
  it('AC-1: fetch 觸發 + data resolved', async () => {
    const { result } = renderHook(() => useCategories(), { wrapper })
    await waitFor(() => {
      expect(result.current.data?.length).toBe(2)
    })
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })

  it('AC-2: data shape 對齊 CategoryCount{name, count}', async () => {
    const { result } = renderHook(() => useCategories(), { wrapper })
    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })
    const first = result.current.data?.[0]
    expect(first?.name).toBe('devops')
    expect(first?.count).toBe(12)
  })

  it('AC-3: error response surfaces via isError state', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: 'Internal' }),
    } as Response)
    const { result } = renderHook(() => useCategories(), { wrapper })
    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })
  })
})
