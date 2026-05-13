import { useQuery } from '@tanstack/react-query'
import { fetchRequests, type RequestsQuery, type SkillRequest } from '../api/skills'

/**
 * S096g2 → S156c — Request Board list with sort（voting-board pivot 後不再支援 status filter）。
 *
 * Cache 30s（vote count change 高頻；reviewer 期望 fresh）；
 * `refetchOnWindowFocus: true` 對齊 useFlagsQueue 既有 pattern（社群協作頁面切回 tab 即 refetch）。
 */
export function useRequests(opts?: RequestsQuery) {
  return useQuery<SkillRequest[]>({
    queryKey: ['requests', opts?.sort ?? 'votes'],
    queryFn: () => fetchRequests(opts),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
