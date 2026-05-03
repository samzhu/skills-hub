import { useQuery } from '@tanstack/react-query'
import { fetchRequest } from '../api/skills'

/**
 * S096g2 — 取單一 Request 詳情；對齊 useSkill 既有 pattern。
 *
 * `enabled` gate id falsy 防止 mount 時 placeholder 觸發 404；list → detail navigation
 * 拿到 id 後自然 fire。
 */
export function useRequest(id: string | undefined) {
  return useQuery({
    queryKey: ['requests', id],
    queryFn: () => fetchRequest(id!),
    enabled: !!id,
  })
}
