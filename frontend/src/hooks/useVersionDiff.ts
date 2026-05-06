import { useQuery } from '@tanstack/react-query'
import { fetchVersionDiff } from '../api/skills'

/** S098c2 — 取得兩版本之間的欄位差異。from/to 任一為空時停用查詢。 */
export function useVersionDiff(skillId: string, from: string | null, to: string | null) {
  return useQuery({
    queryKey: ['skills', skillId, 'diff', from, to],
    queryFn: () => fetchVersionDiff(skillId, from!, to!),
    enabled: !!skillId && !!from && !!to && from !== to,
  })
}
