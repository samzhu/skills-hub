import { apiFetch } from './client'

/**
 * S096h2 — Notification public type，對齊 backend NotificationResponse（spec §4.7）。
 *
 * Field 變動 from S096h1 stub：
 * - `read: boolean` → `readAt: string | null`（讀狀態 + 時間戳；UI 推導 `isRead = readAt != null`）
 * - 新增 `refEventId`（給 future link-back / debug 用；listener idempotency 鍵）
 * - `body` 改 nullable（review notification body 為 null）
 */
export interface Notification {
  id: string
  category: 'flags' | 'reviews' | 'requests' | 'versions'
  title: string
  body: string | null
  skillId: string | null
  refEventId: string
  readAt: string | null
  createdAt: string
}

/** Backend `NotificationListResponse` shape — items + hasNext flag。 */
interface NotificationListResponse {
  items: Notification[]
  hasNext: boolean
}

export interface NotificationPreferences {
  flags: boolean
  reviews: boolean
  requests: boolean
  versions: boolean
}

export interface NotificationsQuery {
  category?: Notification['category']
  cursor?: string
  limit?: number
}

/**
 * Backend 回 wrapper `{items, hasNext}`；MVP 前端只取 items（cursor pagination UI 是 polish，
 * defer 至 follow-up sub-spec）。FE 加 cursor 時改為直接拋 wrapper 給 hook。
 */
export async function fetchNotifications(opts?: NotificationsQuery): Promise<Notification[]> {
  const params = new URLSearchParams()
  if (opts?.category) params.set('category', opts.category)
  if (opts?.cursor) params.set('cursor', opts.cursor)
  if (opts?.limit) params.set('limit', String(opts.limit))
  const qs = params.toString()
  const r = await apiFetch<NotificationListResponse>(`/notifications${qs ? `?${qs}` : ''}`)
  return r.items
}

export function fetchUnreadCount(): Promise<{ count: number }> {
  return apiFetch<{ count: number }>('/notifications/unread-count')
}

export function markNotificationRead(id: string): Promise<void> {
  return apiFetch<void>(`/notifications/${id}/read`, { method: 'POST' })
}

export function markAllNotificationsRead(): Promise<void> {
  return apiFetch<void>('/notifications/read-all', { method: 'POST' })
}

export function deleteNotification(id: string): Promise<void> {
  return apiFetch<void>(`/notifications/${id}`, { method: 'DELETE' })
}

export function fetchPreferences(): Promise<NotificationPreferences> {
  return apiFetch<NotificationPreferences>('/notifications/preferences')
}

/** Partial body — undefined / 不傳 = 該欄位不動（backend 走 `null` 路徑 keep existing）。 */
export function updatePreferences(body: Partial<NotificationPreferences>): Promise<NotificationPreferences> {
  return apiFetch<NotificationPreferences>('/notifications/preferences', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
