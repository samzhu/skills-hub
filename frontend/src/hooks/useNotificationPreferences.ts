import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchPreferences,
  updatePreferences,
  type NotificationPreferences,
} from '@/api/notifications'

/**
 * S096h2 — User notification preferences (4 toggles)。
 *
 * Cache 寬鬆（5 分鐘）— 設定變動頻率低；mutation 走 setQueryData immediate update 跳過
 * refetch。`enabled: false` 不暴露（modal 開啟才 mount，自然 lazy fetch）。
 */
export function useNotificationPreferences() {
  return useQuery<NotificationPreferences>({
    queryKey: ['notification-preferences'],
    queryFn: fetchPreferences,
    staleTime: 5 * 60 * 1000,
  })
}

/**
 * Partial update — onSuccess 直接 setQueryData 而非 invalidate（mutation response 已含
 * full preferences 物件）。後續 listener gate 結果由下個 server-side event 自動驗證。
 */
export function useUpdateNotificationPreferences() {
  const qc = useQueryClient()
  return useMutation<NotificationPreferences, Error, Partial<NotificationPreferences>>({
    mutationFn: updatePreferences,
    onSuccess: (data) => {
      qc.setQueryData(['notification-preferences'], data)
    },
  })
}
