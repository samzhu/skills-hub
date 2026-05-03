import { apiFetch } from './client'

/**
 * S112 — Skill 舉報（Flag）API client。
 *
 * `type` enum 對齊後端 `FlagService.ALLOWED_TYPES`（S072）。
 * `status` 目前僅 OPEN 由 backend 寫入；RESOLVED 預留給未來 reviewer 處理流程（S098e3）。
 */
export interface Flag {
  id: string
  skillId: string
  type: 'malicious' | 'spam' | 'inappropriate' | 'copyright' | 'security' | 'other'
  description: string | null
  reportedBy: string
  createdAt: string
  status: 'OPEN' | 'RESOLVED'
}

export function fetchFlags(skillId: string): Promise<Flag[]> {
  return apiFetch<Flag[]>(`/skills/${skillId}/flags`)
}

/**
 * S112 — MySkillsPage「待處理回報」MetricCard 用的 aggregate count。
 * Backend：`GET /api/v1/me/flags-summary` 回 `{openCount: number}`，僅統計
 * current user 名下 PUBLISHED skill 的 OPEN flag 總數。
 */
export interface FlagsSummary {
  openCount: number
}

export function fetchFlagsSummary(): Promise<FlagsSummary> {
  return apiFetch<FlagsSummary>('/me/flags-summary')
}
