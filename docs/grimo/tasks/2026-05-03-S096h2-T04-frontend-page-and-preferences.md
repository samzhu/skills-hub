# S096h2-T04: Frontend api/notifications.ts + hooks + NotificationsPage 改寫 + PreferencesModal

## Spec
S096h2 — Notifications Full Projection（spec doc: `docs/grimo/specs/2026-05-03-S096h2-notifications-projection.md`）

## BDD（涵蓋的 AC）

**AC-12: NotificationsPage — list + filter chips + mark-read + delete**
- Given：alice 收 5 筆 notification (3 flags / 2 reviews) 全 unread
- When：user 開 `/notifications`
- Then：顯 5 筆 row（按 createdAt desc）+ 上方 category filter chips（全部 / 動態 / 評論 / 需求）+「全部已讀」hero button + 單筆右側 ✕ 按鈕
- 點「全部已讀」→ POST read-all → 5 筆變灰
- 點 row 主體 → POST {id}/read → 該筆變灰
- 點 ✕ → DELETE {id} → 該筆消失

**AC-13: AppShell bell badge — 真 count**
- Given：alice 收新 notification（projection 寫入後）
- When：30s polling tick 觸發
- Then：bell badge 顯 unread count；既有 30s poll 機制無需改

**AC-14: Preferences modal — 4 toggle**
- Given：alice 預設全 enabled
- When：alice 在 NotificationsPage 點「設定」（CTA 從 disabled 改 active）→ 開 PreferencesModal → toggle flags off → save
- Then：發 POST preferences；下次 SkillFlagged for alice's skill 不收

## Implementation outline

### `frontend/src/api/notifications.ts` (new — split from skills.ts)

```typescript
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

export interface NotificationPreferences {
  flags: boolean
  reviews: boolean
  requests: boolean
  versions: boolean
}

export function fetchNotifications(opts?: { category?: Notification['category']; cursor?: string; limit?: number }): Promise<Notification[]>
export function fetchUnreadCount(): Promise<{ count: number }>
export function markNotificationRead(id: string): Promise<void>
export function markAllNotificationsRead(): Promise<void>
export function deleteNotification(id: string): Promise<void>
export function fetchPreferences(): Promise<NotificationPreferences>
export function updatePreferences(p: Partial<NotificationPreferences>): Promise<NotificationPreferences>
```

### `frontend/src/api/skills.ts` (modify — 移除舊 Notification + 2 fetchers)

caller 改 import from `'@/api/notifications'`。AppShell 是主要 caller。

### `frontend/src/hooks/useNotifications.ts` (new)

```typescript
export function useNotifications(category?: Notification['category']) {
  return useQuery({
    queryKey: ['notifications', category ?? 'all'],
    queryFn: () => fetchNotifications({ category }),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
```

### `frontend/src/hooks/useNotificationPreferences.ts` (new)

```typescript
export function useNotificationPreferences() {
  return useQuery({ queryKey: ['notification-preferences'], queryFn: fetchPreferences })
}
```

### `frontend/src/components/PreferencesModal.tsx` (new)

mirror CreateRequestModal / FlagSubmitModal pattern：4 個 toggle (checkbox or switch) + Submit。
useMutation invalidate `['notification-preferences']`.

### `frontend/src/pages/NotificationsPage.tsx` (modify — 取代 stub-style EmptyState 為真 list)

- Hero 加 category filter chips (全部 / flags 動態 / reviews 評論 / requests 需求)
- 「全部已讀」CTA → useMutation markAllRead → invalidate
- 「設定」button enable → 開 PreferencesModal
- list rows: 點 row 主體 → mark single read; 點 ✕ → delete (with confirm)
- 0 rows → 退 EmptyState

### Tests `frontend/src/pages/NotificationsPage.test.tsx` (new)

URL-aware fetch mock：
- AC-12: 5 筆 mock → 5 row 渲染；點全部已讀 → POST read-all 觸發；點 ✕ → DELETE 觸發
- AC-14: 點「設定」→ PreferencesModal 開 → toggle flags off → POST preferences body `{flags: false}`

## Target Files

- `frontend/src/api/notifications.ts` (new — split from skills.ts)
- `frontend/src/api/skills.ts` (modify — 移除舊 Notification interface + 2 fetchers)
- `frontend/src/hooks/useNotifications.ts` (new)
- `frontend/src/hooks/useNotificationPreferences.ts` (new)
- `frontend/src/components/PreferencesModal.tsx` (new — 4 toggle 表單)
- `frontend/src/pages/NotificationsPage.tsx` (modify — list + filter chips + mark-read + delete + Preferences trigger)
- `frontend/src/components/AppShell.tsx` (no change — 既有 30s poll 自動接真資料；AppShell.test.tsx 若 mock notification import 需順 update)
- `frontend/src/pages/NotificationsPage.test.tsx` (new — AC-12/14)

## Depends On
- T01 + T02 + T03（backend endpoints + listener 全 ship）
- 既有 30s poll AppShell pattern（無改動需求）

## Status
pending
