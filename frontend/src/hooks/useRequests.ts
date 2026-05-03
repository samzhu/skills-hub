import { useQuery } from '@tanstack/react-query'
import { fetchRequests, type RequestsQuery, type SkillRequest } from '../api/skills'

/**
 * S096g2 — Request Board list with sort + status filter。
 *
 * Cache 30s（vote count change 高頻；reviewer 期望 fresh）；
 * `refetchOnWindowFocus: true` 對齊 useFlagsQueue 既有 pattern（社群協作頁面切回 tab 即 refetch）。
 *
 * Cache key 內嵌 sort + status，filter chip 切換時各自獨立 cache（避免互相覆寫）。
 */
export function useRequests(opts?: RequestsQuery) {
  return useQuery<SkillRequest[]>({
    queryKey: ['requests', opts?.sort ?? 'votes', opts?.status ?? 'all'],
    queryFn: () => fetchRequests(opts),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
