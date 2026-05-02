import { apiFetch } from './client'

/**
 * 平台整體概覽統計，由後端 AnalyticsService 彙整後回傳。
 */
export interface OverviewStats {
  /** 平台上的技能總數 */
  totalSkills: number
  /** 累計下載總次數（跨所有技能） */
  totalDownloads: number
  /** 本週（最近 7 天）新增的技能數量 */
  newSkillsThisWeek: number
  /**
   * 下載數前 N 名的技能（後端預設取 Top 10）。
   * 依 downloadCount 降冪排序，第一筆為下載數最高的技能。
   * S100a：加 author 用於 frontend Link 至 canonical /skills/:author/:name
   */
  topSkills: { name: string; author: string; downloads: number }[]
}

/**
 * 取得平台整體概覽統計資料。
 *
 * @returns 包含技能總數、下載總數、本週新增及熱門排行的統計物件
 */
export function fetchOverview(): Promise<OverviewStats> {
  return apiFetch('/analytics/overview')
}
