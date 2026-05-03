import { apiFetch } from './client'

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
  return apiFetch<void>(`/skills/${encodeURIComponent(skillId)}/subscribe`, {
    method: 'POST',
  })
}

export function unsubscribeSkill(skillId: string): Promise<void> {
  return apiFetch<void>(`/skills/${encodeURIComponent(skillId)}/subscribe`, {
    method: 'DELETE',
  })
}

/** 回當前 user 訂閱的所有 skillId list（順序由 DB 決定）。 */
export function fetchMySubscriptions(): Promise<string[]> {
  return apiFetch<string[]>('/me/subscriptions')
}
