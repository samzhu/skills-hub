import { useQuery } from '@tanstack/react-query'
import { fetchCollections, type SkillCollection } from '@/api/skills'

/**
 * S096f2-T03 — Collections list with optional category filter。
 *
 * Cache 30s 對齊 useRequests / useNotifications 既驗 staleTime；filter chips 切換各自獨立
 * cache（queryKey 含 category）。`refetchOnWindowFocus` 給「切回 tab 即時更新」UX。
 */
export function useCollections(category?: string) {
  return useQuery<SkillCollection[]>({
    queryKey: ['collections', category ?? 'all'],
    queryFn: () => fetchCollections(category),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
