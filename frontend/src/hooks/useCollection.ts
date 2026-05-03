import { useQuery } from '@tanstack/react-query'
import { fetchCollection, type CollectionDetail } from '@/api/skills'

/**
 * S096f2-T03 — Collection detail with skills summary（GET /collections/{id}）。
 *
 * `enabled: !!id` 對齊 useSkill / useRequest pattern — id undefined 時不 fetch。
 * Cache 30s（detail 不常變動 + install_count 30s 內 stale 可接受）。
 */
export function useCollection(id: string | undefined) {
  return useQuery<CollectionDetail>({
    queryKey: ['collection', id],
    queryFn: () => fetchCollection(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}
