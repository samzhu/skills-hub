import { useQuery } from '@tanstack/react-query'
import { fetchSearchIntent, type IntentResponse } from '../api/search'

/**
 * S094b — fetch LLM intent summary + concept tags for a search query.
 *
 * Stale 5min（query string 為 stable cache key）；網路 4xx 不 retry（per S064/S065 既有政策）。
 * Backend graceful fallback：若 LLM 未啟用，回 {summary: query, concepts: []} —
 * 前端據 concepts.length 判斷是否顯 IntentSummaryCard。
 */
export function useSearchIntent(query: string | undefined) {
  return useQuery<IntentResponse>({
    queryKey: ['search-intent', query],
    queryFn: () => fetchSearchIntent(query!),
    enabled: !!query && query.trim().length > 0,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
