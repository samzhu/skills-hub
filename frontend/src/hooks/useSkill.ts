import { useQuery } from '@tanstack/react-query'
import { fetchSkillById } from '../api/skills'

/**
 * 依 ID 取得單一技能詳情的 React Query hook。
 *
 * 快取鍵為 `['skills', id]`，讓同一技能在不同元件間共享快取，
 * 避免重複請求。
 *
 * @param id 技能 UUID；傳入空字串時自動停用查詢（`enabled: false`），
 *           防止因 useParams 尚未解析而發出無效請求
 * @returns React Query 查詢結果，包含 data（Skill）、isLoading、error
 */
export function useSkill(id: string) {
  return useQuery({
    queryKey: ['skills', id],
    queryFn: () => fetchSkillById(id),
    // id 為空字串（useParams 尚未解析）時停用查詢，
    // !!id 同時處理 undefined 與 '' 兩種 falsy 值
    enabled: !!id,
  })
}
