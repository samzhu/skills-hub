import { apiFetch, apiFetchVoid } from './client'
import type { RiskLevel, SkillStatus } from '@/types/skill'

/**
 * S125c — SkillSubscription HTTP helpers（對齊 S125b backend endpoints）。
 *
 * 三個 API 對應 backend SkillSubscriptionController：
 * - subscribeSkill(skillId)   → POST /skills/{id}/subscribe (201)
 * - unsubscribeSkill(skillId) → DELETE /skills/{id}/subscribe (204)
 * - fetchMySubscriptions()    → GET /me/subscriptions (200 List<String>)
 *
 * 對齊 notifications.ts / collections.ts 既驗 helper pattern：
 * - apiFetch wrapper 統一 error handling + base URL
 * - 純函式（不含 React Query）；hook 層 (useSubscription) 處理 cache invalidation
 */

export function subscribeSkill(skillId: string): Promise<void> {
  return apiFetchVoid(`/skills/${encodeURIComponent(skillId)}/subscribe`, {
    method: 'POST',
  })
}

export function unsubscribeSkill(skillId: string): Promise<void> {
  return apiFetchVoid(`/skills/${encodeURIComponent(skillId)}/subscribe`, {
    method: 'DELETE',
  })
}

/** 回當前 user 訂閱的所有 skillId list（順序由 DB 決定）。 */
export function fetchMySubscriptions(): Promise<string[]> {
  return apiFetch<string[]>('/me/subscriptions')
}

/** S145 — 我的訂閱管理列表每列需要的 skill 摘要。 */
export interface SubscriptionSummary {
  skillId: string
  skillName: string
  author: string
  authorDisplayName: string | null
  latestVersion: string | null
  riskLevel: RiskLevel | null
  status: SkillStatus
  subscribedAt: string
}

/** 回當前 user 訂閱的所有 skill summary rows，供「我的技能 / 訂閱」tab 使用。 */
export function fetchMySubscriptionDetails(): Promise<SubscriptionSummary[]> {
  return apiFetch<SubscriptionSummary[]>('/me/subscriptions/details')
}
