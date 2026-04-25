import { useQuery } from '@tanstack/react-query'
import { fetchVersions } from '../api/skills'

/**
 * 取得某技能所有版本清單的 React Query hook。
 *
 * 快取鍵為 `['skills', skillId, 'versions']`，與技能詳情快取鍵層次一致，
 * 方便在新增版本後一起 invalidate。
 *
 * @param skillId 目標技能的 UUID；空字串時停用查詢，與 `useSkill` 保持相同防禦策略
 * @returns React Query 查詢結果，包含 data（SkillVersion[]）、isLoading、error
 */
export function useVersions(skillId: string) {
  return useQuery({
    queryKey: ['skills', skillId, 'versions'],
    queryFn: () => fetchVersions(skillId),
    // skillId 為空字串時停用，防止因路由參數尚未解析而發出 /skills//versions 請求
    enabled: !!skillId,
  })
}
