import { useInfiniteQuery } from '@tanstack/react-query'
import { fetchSemanticSearch } from '@/api/search'
import type { SemanticSearchResult, SpringSlice } from '@/types/skill'

/**
 * 語意搜尋 React Query hook — 以自然語言查詢技能，逐頁 append Slice.content。
 *
 * 只在 query 非空時發出請求（`enabled: query.trim().length > 0`），
 * 避免空查詢觸發 API 呼叫。快取鍵包含查詢字串，確保不同查詢各自獨立快取。
 *
 * @param query 使用者輸入的自然語言查詢
 * @param size 每頁筆數
 * @returns React Query infinite 查詢結果，data.pages 是 Spring Slice 陣列
 */
export function useInfiniteSemanticSearch(query: string, size = 10) {
  const trimmedQuery = query.trim()
  return useInfiniteQuery<SpringSlice<SemanticSearchResult>, Error>({
    queryKey: ['search', 'semantic', trimmedQuery, size],
    queryFn: ({ pageParam }) => fetchSemanticSearch({
      q: trimmedQuery,
      page: Number(pageParam),
      size,
    }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    // 空查詢不觸發 API 呼叫 — 由 HomePage 根據 query 是否非空決定顯示模式。
    enabled: trimmedQuery.length > 0,
  })
}
