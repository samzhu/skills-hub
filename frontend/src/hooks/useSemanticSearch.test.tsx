import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { SemanticSearchResult, SpringSlice } from '@/types/skill'
import { useInfiniteSemanticSearch } from './useSemanticSearch'
import * as searchApi from '@/api/search'

// vi.mock 靜態提升至 module 頂；hook 內部 import 的 fetchSemanticSearch 會被攔截
vi.mock('@/api/search')

const mockFetchSemanticSearch = vi.mocked(searchApi.fetchSemanticSearch)

// 每個 test 獨立 QueryClient — 避免 cache 污染相鄰 test；
// retry: false 確保 mock rejection 不會多次重試導致 timeout
const createWrapper = () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const slice = (
  content: SemanticSearchResult[],
  number: number,
  last: boolean,
): SpringSlice<SemanticSearchResult> => ({
  content,
  first: number === 0,
  last,
  number,
  size: 10,
  numberOfElements: content.length,
})

describe('AC-S203-4: useInfiniteSemanticSearch hook', () => {
  beforeEach(() => {
    mockFetchSemanticSearch.mockReset()
  })

  it('空 query（""）→ enabled: false → fetchSemanticSearch 不被呼叫', () => {
    renderHook(() => useInfiniteSemanticSearch(''), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('純空白 query（"   "）→ trim().length === 0 → enabled: false 不觸發', () => {
    renderHook(() => useInfiniteSemanticSearch('   '), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('AC-S203-4: fetches page 0 and page 1 from Slice.last', async () => {
    const page0: SemanticSearchResult[] = [
      {
        id: 'skill-001',
        name: 'k8s-deployer',
        description: 'Kubernetes 部署助理',
        author: 'samzhu',
        category: '雲端維運',
        latestVersion: '0.1.0',
        riskLevel: 'LOW',
        downloadCount: 10,
        score: 0.92,
      },
    ]
    const page1: SemanticSearchResult[] = [
      {
        id: 'skill-002',
        name: 'docker-runner',
        description: 'Docker 部署助理',
        author: 'samzhu',
        category: '雲端維運',
        latestVersion: '0.2.0',
        riskLevel: 'LOW',
        downloadCount: 8,
        score: 0.87,
      },
    ]
    mockFetchSemanticSearch
      .mockResolvedValueOnce(slice(page0, 0, false))
      .mockResolvedValueOnce(slice(page1, 1, true))

    const { result } = renderHook(() => useInfiniteSemanticSearch('Kubernetes'), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith({ q: 'Kubernetes', page: 0, size: 10 })
    expect(result.current.hasNextPage).toBe(true)

    await result.current.fetchNextPage()

    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2))
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith({ q: 'Kubernetes', page: 1, size: 10 })
    expect(result.current.data?.pages.flatMap((p) => p.content)).toEqual([...page0, ...page1])
  })

  it('queryKey 含 query 字串 — 不同 query 各自獨立 cache（無共享）', async () => {
    mockFetchSemanticSearch.mockResolvedValue(slice([], 0, true))
    const { result: r1 } = renderHook(() => useInfiniteSemanticSearch('Docker'), {
      wrapper: createWrapper(),
    })
    const { result: r2 } = renderHook(() => useInfiniteSemanticSearch('Kubernetes'), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(r1.current.isSuccess).toBe(true))
    await waitFor(() => expect(r2.current.isSuccess).toBe(true))
    // 兩次不同 query → API 呼叫兩次（cache 不共享 — 不同 wrapper 也不同 client，但即便同 client 不同 queryKey 也不共享）
    expect(mockFetchSemanticSearch).toHaveBeenCalledTimes(2)
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith({ q: 'Docker', page: 0, size: 10 })
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith({ q: 'Kubernetes', page: 0, size: 10 })
  })
})
