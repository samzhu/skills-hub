import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteNotification,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type Notification,
} from '@/api/notifications'

/**
 * S096h2 — Notification list with optional category filter。
 *
 * Cache 30s 對齊 useRequests / AppShell bell badge 既有 staleTime；filter chips 切換各自獨立
 * cache（queryKey 含 category）。`refetchOnWindowFocus` 給「切回 tab 即時更新」UX。
 */
export function useNotifications(category?: Notification['category']) {
  return useQuery<Notification[]>({
    queryKey: ['notifications', category ?? 'all'],
    queryFn: () => fetchNotifications({ category }),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}

/**
 * Mark single notification as read。Mutation 完 invalidate 所有 `['notifications', *]` 變體
 * + AppShell bell badge `['notifications-unread']` — 確保 list + badge 同步更新。
 */
export function useMarkNotificationRead() {
  const qc = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: markNotificationRead,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] })
      qc.invalidateQueries({ queryKey: ['notifications-unread'] })
    },
  })
}

/** Mark all unread notifications as read for current user。 */
export function useMarkAllNotificationsRead() {
  const qc = useQueryClient()
  return useMutation<void, Error, void>({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] })
      qc.invalidateQueries({ queryKey: ['notifications-unread'] })
    },
  })
}

/** Hard delete single notification — row 從 list 消失 + badge 同步。 */
export function useDeleteNotification() {
  const qc = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: deleteNotification,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications'] })
      qc.invalidateQueries({ queryKey: ['notifications-unread'] })
    },
  })
}
