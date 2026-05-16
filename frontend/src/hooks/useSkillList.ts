import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { fetchSkills, type SkillSearchParams } from '../api/skills'

interface UseSkillListOptions {
  enabled?: boolean
}

/**
 * 搜尋技能列表（含分頁）的 React Query hook。
 *
 * 快取鍵包含完整的 params 物件，確保不同搜尋條件各自獨立快取。
 *
 * @param params 搜尋條件（keyword、category、page、size）
 * @param options 查詢開關；未傳時維持既有自動 fetch
 * @returns React Query 查詢結果，包含 data（SpringPage<Skill>）、isLoading、error
 */
export function useSkillList(params: SkillSearchParams, options?: UseSkillListOptions) {
  return useQuery({
    queryKey: ['skills', 'list', params],
    queryFn: () => fetchSkills(params),
    enabled: options?.enabled ?? true,
    // keepPreviousData：翻頁或切換篩選條件時，在新資料載入完成前繼續顯示上一頁的結果，
    // 避免清單瞬間變空（閃爍）造成不良使用者體驗。
    // 注意：React Query v5 將 v4 的 keepPreviousData: true 改為此 placeholderData 寫法。
    placeholderData: keepPreviousData,
  })
}
