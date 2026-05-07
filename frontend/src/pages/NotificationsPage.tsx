import { useState } from 'react'
import { Bell, X } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { PreferencesModal } from '@/components/PreferencesModal'
import {
  useDeleteNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
} from '@/hooks/useNotifications'
import { useAuth } from '@/hooks/useAuth'
import type { Notification } from '@/api/notifications'

/**
 * S096h2-T04 — Notification Center 完整 projection 版（取代 S096h1 read-only stub）。
 *
 * - Hero：通知中心 + filter chips + 全部已讀 + 設定 buttons
 * - List：rows or EmptyState
 * - Modal：PreferencesModal triggered from「設定」button
 *
 * Versions chip 不顯（spec §2.6 trim — listener 不產 versions 通知）。
 * Delete 無 confirm（MVP；UX polish 留 follow-up）。
 */

type CategoryFilter = 'all' | 'flags' | 'reviews' | 'requests'

const CATEGORY_FILTERS: ReadonlyArray<{ key: CategoryFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'flags', label: '回報' },
  { key: 'reviews', label: '評論' },
  { key: 'requests', label: '需求' },
] as const

export function NotificationsPage() {
  // S139：anonymous 早 return CTA card；authenticated 才 mount 既有 list / mutations
  // （hooks 順序固定：useAuth 必須在所有 hooks 之前；anonymous 早 return 前不能用其他
  // useQuery/useMutation，避免「hook 數不一致」）
  const auth = useAuth()
  const [filter, setFilter] = useState<CategoryFilter>('all')
  const [showPreferences, setShowPreferences] = useState(false)
  const { data: notifications, isLoading } = useNotifications(
    filter === 'all' ? undefined : filter,
  )
  const markRead = useMarkNotificationRead()
  const markAllRead = useMarkAllNotificationsRead()
  const deleteNotif = useDeleteNotification()

  const hasItems = !!notifications && notifications.length > 0

  // S139：anonymous CTA — useNotifications 已跑完（hooks 順序鎖定），但 result
  // 對 anonymous 必為 401 → notifications=undefined → hasItems=false。直接渲染
  // CTA card 替代既有 EmptyState，含登入按鈕。
  if (auth.status === 'anonymous') {
    return (
      <AppShell>
        <div className="mb-6">
          <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">即時更新</p>
          <h1 className="mt-1 text-[22px] font-semibold tracking-tight">通知中心</h1>
        </div>
        <EmptyState
          tone="invite"
          headline="登入後查看通知"
          sub="登入後可看到收藏技能的版本更新、被回報的 flag 進度、以及 review/收到的留言。"
        />
        <div className="mt-4 flex justify-center">
          <button
            type="button"
            onClick={() => auth.login()}
            className="inline-flex h-9 items-center justify-center rounded-md bg-primary px-5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            登入
          </button>
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">即時更新</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">通知中心</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          訂閱的 skill 有新版本、被回報、進入審核、需求看板回應 — 都會出現在這裡。
        </p>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          {CATEGORY_FILTERS.map((c) => (
            <button
              key={c.key}
              type="button"
              onClick={() => setFilter(c.key)}
              className={`rounded-full px-3 py-1 text-[12px] ${
                filter === c.key
                  ? 'bg-primary text-primary-foreground'
                  : 'border border-border text-muted-foreground hover:bg-muted'
              }`}
            >
              {c.label}
            </button>
          ))}
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => markAllRead.mutate()}
            disabled={!hasItems || markAllRead.isPending}
            className="rounded-md border border-border px-3 py-1 text-[12px] hover:bg-muted disabled:opacity-50"
          >
            {markAllRead.isPending ? '處理中...' : '全部已讀'}
          </button>
          <button
            type="button"
            onClick={() => setShowPreferences(true)}
            className="rounded-md border border-border px-3 py-1 text-[12px] hover:bg-muted"
          >
            設定
          </button>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !hasItems && filter !== 'all' ? (
        <EmptyState
          tone="redirect"
          headline={`此分類（${CATEGORY_FILTERS.find((c) => c.key === filter)?.label}）目前沒有通知。`}
          sub="試試選擇「全部」查看所有通知，或等待相關事件觸發。"
          primaryAction={{ label: '查看全部通知', onClick: () => setFilter('all') }}
        />
      ) : !hasItems ? (
        <EmptyState
          tone="clear"
          headline="都看完了，沒有未讀通知。"
          sub="當你訂閱的 skill 發布新版本、收到回報、進入審核時，新通知會即時出現在這裡。"
          stats={[
            { value: '0', label: '本週新通知' },
            { value: '0', label: '未讀' },
            { value: '—', label: '上次接收' },
          ]}
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {notifications.map((n, i) => (
            <NotificationRow
              key={n.id}
              notif={n}
              isLast={i === notifications.length - 1}
              onMarkRead={() => markRead.mutate(n.id)}
              onDelete={() => deleteNotif.mutate(n.id)}
            />
          ))}
        </div>
      )}

      {showPreferences && <PreferencesModal onClose={() => setShowPreferences(false)} />}
    </AppShell>
  )
}

function NotificationRow({
  notif,
  isLast,
  onMarkRead,
  onDelete,
}: {
  notif: Notification
  isLast: boolean
  onMarkRead: () => void
  onDelete: () => void
}) {
  const isRead = notif.readAt !== null

  return (
    <div
      className={
        'flex items-start gap-3 px-4 py-3 hover:bg-muted/30 ' +
        (isRead ? 'opacity-60 ' : '') +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <CategoryDot category={notif.category} />
      <button
        type="button"
        onClick={isRead ? undefined : onMarkRead}
        disabled={isRead}
        className="min-w-0 flex-1 cursor-pointer text-left disabled:cursor-default"
      >
        <div className="flex items-center gap-2">
          <span className="text-[13px] font-medium">{notif.title}</span>
          {!isRead && <span className="h-1.5 w-1.5 rounded-full bg-[#7F77DD]" aria-label="未讀" />}
        </div>
        {notif.body && (
          <p className="mt-0.5 text-[12px] leading-relaxed text-muted-foreground">{notif.body}</p>
        )}
      </button>
      <div className="shrink-0 text-[11px] text-muted-foreground">
        {new Date(notif.createdAt).toLocaleDateString('zh-TW')}
      </div>
      <button
        type="button"
        onClick={onDelete}
        aria-label="刪除通知"
        className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

function CategoryDot({ category }: { category: Notification['category'] }) {
  const colorMap: Record<Notification['category'], string> = {
    versions: '#7F77DD', // accent purple — version published
    flags: '#E24B4A',    // danger — flag raised
    reviews: '#EF9F27',  // warning — review created
    requests: '#378ADD', // info — request claim/fulfill
  }
  return (
    <Bell
      className="mt-0.5 h-4 w-4 shrink-0"
      style={{ color: colorMap[category] }}
      aria-label={`${category} 通知`}
    />
  )
}
