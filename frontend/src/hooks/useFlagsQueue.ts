import { useQuery } from '@tanstack/react-query'
import { fetchFlagsByStatus, type Flag } from '../api/flags'

/**
 * S098e3 — fetch cross-skill flags for reviewer queue page。
 *
 * Cache 30s（reviewer queue 期望 fresh，比 useFlags 60s 短）；
 * `refetchOnWindowFocus: true` — reviewer 切換 tab 回來時 refetch（與
 * useFlags 不同；queue 頁面更需即時）。
 *
 * @param status `'OPEN' | 'RESOLVED' | 'DISMISSED' | undefined`；undefined 回全部
 */
export function useFlagsQueue(status?: Flag['status']) {
  return useQuery<Flag[]>({
    queryKey: ['flags-queue', status ?? 'all'],
    queryFn: () => fetchFlagsByStatus(status),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
