import { useQuery } from '@tanstack/react-query'
import { fetchFlagsSummary, type FlagsSummary } from '../api/flags'

/**
 * S112 — fetch current user 的 OPEN flag aggregate count（限 PUBLISHED skill）。
 * 用於 MySkillsPage「待處理回報」MetricCard，避免 N+1 fetch。
 * Cache 60s（per `useFlags` pattern）；網路 4xx 不 retry。
 */
export function useFlagsSummary(enabled: boolean = true) {
  return useQuery<FlagsSummary>({
    queryKey: ['me-flags-summary'],
    queryFn: fetchFlagsSummary,
    enabled,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
