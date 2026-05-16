import { apiFetch, apiFetchVoid } from './client'

/**
 * S112 + S098e3 — Skill 舉報（Flag）API client。
 *
 * `type` enum 對齊後端 `FlagService.ALLOWED_TYPES`（S072）。
 * `status`：OPEN（初始）/ RESOLVED（已處理）/ DISMISSED（駁回）— 對齊 S098e3 backend FlagStatus enum。
 */
export interface Flag {
  id: string
  skillId: string
  type: 'malicious' | 'spam' | 'inappropriate' | 'copyright' | 'security' | 'other'
  description: string | null
  reportedBy: string
  createdAt: string
  status: 'OPEN' | 'RESOLVED' | 'DISMISSED'
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

/**
 * S098e3 — 提交新 flag。
 * Backend：`POST /api/v1/skills/{skillId}/flags` body `{type, description?}`。
 * `description` optional — backend 接受 null。Reporter identity 由 backend 從
 * Authorization header 抽 sub（fallback "anonymous"）；frontend 不傳。
 */
export interface CreateFlagBody {
  type: Flag['type']
  description?: string
}

export function createFlag(skillId: string, body: CreateFlagBody): Promise<{ id: string }> {
  return apiFetch<{ id: string }>(`/skills/${skillId}/flags`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/**
 * S098e3 — Cross-skill flag listing for reviewer queue page。
 * Backend：`GET /api/v1/flags?status=OPEN`。
 */
export function fetchFlagsByStatus(status?: Flag['status']): Promise<Flag[]> {
  const path = status ? `/flags?status=${status}` : '/flags'
  return apiFetch<Flag[]>(path)
}

/**
 * S098e3 — Reviewer 處理 flag。
 * Backend：`PATCH /api/v1/skills/{skillId}/flags/{flagId}` body `{status: "RESOLVED"|"DISMISSED"}`。
 * Backend 驗 transition (OPEN→RESOLVED/DISMISSED only)；違規 400，flag 不存在 404。
 */
export function updateFlagStatus(
  skillId: string,
  flagId: string,
  status: Flag['status'],
): Promise<void> {
  return apiFetchVoid(`/skills/${skillId}/flags/${flagId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}
