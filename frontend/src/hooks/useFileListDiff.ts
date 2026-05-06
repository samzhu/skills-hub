import { useQuery } from '@tanstack/react-query'
import { fetchFileListDiff } from '@/api/skills'

/** S098c3 — 取得兩版本 zip 包的檔案列表差異。 */
export function useFileListDiff(skillId: string, from: string | null, to: string | null) {
  return useQuery({
    queryKey: ['skills', skillId, 'file-list-diff', from, to],
    queryFn: () => fetchFileListDiff(skillId, from!, to!),
    enabled: !!skillId && !!from && !!to && from !== to,
  })
}
