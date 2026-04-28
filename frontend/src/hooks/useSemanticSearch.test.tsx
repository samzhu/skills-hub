import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { SemanticSearchResult } from '@/types/skill'
import { useSemanticSearch } from './useSemanticSearch'
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

describe('AC-4: useSemanticSearch hook', () => {
  beforeEach(() => {
    mockFetchSemanticSearch.mockReset()
  })

  it('空 query（""）→ enabled: false → fetchSemanticSearch 不被呼叫', () => {
    renderHook(() => useSemanticSearch(''), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('純空白 query（"   "）→ trim().length === 0 → enabled: false 不觸發', () => {
    renderHook(() => useSemanticSearch('   '), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('非空 query → fetchSemanticSearch 被呼叫 1 次 + data 為 mock 回傳', async () => {
    const mockResults: SemanticSearchResult[] = [
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
    mockFetchSemanticSearch.mockResolvedValue(mockResults)
    const { result } = renderHook(() => useSemanticSearch('Kubernetes'), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetchSemanticSearch).toHaveBeenCalledOnce()
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith('Kubernetes')
    expect(result.current.data).toEqual(mockResults)
  })

  it('queryKey 含 query 字串 — 不同 query 各自獨立 cache（無共享）', async () => {
    mockFetchSemanticSearch.mockResolvedValue([])
    const { result: r1 } = renderHook(() => useSemanticSearch('Docker'), {
      wrapper: createWrapper(),
    })
    const { result: r2 } = renderHook(() => useSemanticSearch('Kubernetes'), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(r1.current.isSuccess).toBe(true))
    await waitFor(() => expect(r2.current.isSuccess).toBe(true))
    // 兩次不同 query → API 呼叫兩次（cache 不共享 — 不同 wrapper 也不同 client，但即便同 client 不同 queryKey 也不共享）
    expect(mockFetchSemanticSearch).toHaveBeenCalledTimes(2)
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith('Docker')
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith('Kubernetes')
  })
})
