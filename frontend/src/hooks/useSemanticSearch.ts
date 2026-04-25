import { useQuery } from '@tanstack/react-query'
import { fetchSemanticSearch } from '@/api/search'

/**
 * 語意搜尋 React Query hook — 以自然語言查詢技能。
 *
 * 只在 query 非空時發出請求（`enabled: query.trim().length > 0`），
 * 避免空查詢觸發 API 呼叫。快取鍵包含查詢字串，確保不同查詢各自獨立快取。
 *
 * @param query 使用者輸入的自然語言查詢
 * @returns React Query 查詢結果，包含 data（SemanticSearchResult[]）、isLoading、error
 */
export function useSemanticSearch(query: string) {
  return useQuery({
    queryKey: ['search', 'semantic', query],
    queryFn: () => fetchSemanticSearch(query),
    // 空查詢不觸發 API 呼叫 — 由 HomePage 根據 query 是否非空決定顯示模式
    enabled: query.trim().length > 0,
  })
}
