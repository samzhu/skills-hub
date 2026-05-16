import { useQuery } from '@tanstack/react-query'
import { fetchSkillById, fetchSkillByAuthorAndName } from '../api/skills'
import { skillKeys } from '../api/queryKeys'

/**
 * 依 ID 取得單一技能詳情的 React Query hook。
 * 快取鍵為 `['skills', id]`。
 */
export function useSkill(id: string) {
  return useQuery({
    queryKey: skillKeys.detail(id),
    queryFn: () => fetchSkillById(id),
    enabled: !!id,
  })
}

/**
 * S096c — 依 (author, name) canonical route 取得 Skill (per ADR-003).
 * 快取鍵 `['skills', 'by-author-name', author, name]` — 與 `useSkill(id)` 隔離，
 * 同一 skill 兩種 query 各自 cache（acceptable redundancy；React Query 會在
 * 兩個 hit 後 dedupe）。
 */
export function useSkillByAuthorAndName(author: string | undefined, name: string | undefined) {
  return useQuery({
    queryKey: skillKeys.byAuthorName(author, name),
    queryFn: () => fetchSkillByAuthorAndName(author!, name!),
    enabled: !!author && !!name,
  })
}
