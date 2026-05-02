import { useQuery } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { fetchNotifications, type Notification } from '@/api/skills'

/**
 * S096h1 — Notification Center read-only stub at `/notifications`.
 *
 * 對齊 PRD §P9 + Engineering Handoff §2.17. 本 spec ship 為 read-only stub：
 * - GET /api/v1/notifications → list（backend stub returns []）
 * - 顯 row list with category icon / title / body / time
 * - 0 results → EmptyState clear tone「都看完了，沒有未讀通知」
 *
 * Defer S096h2: per-user subscription filter / 真實 projection from domain_events /
 * mark-read mutation / preferences endpoint / Version Diff page.
 */
export function NotificationsPage() {
  const { data: notifications, isLoading } = useQuery<Notification[]>({
    queryKey: ['notifications'],
    queryFn: fetchNotifications,
    staleTime: 30 * 1000,
  })

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">通知中心</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">Notifications</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          訂閱的 skill 有新版本、被 flag、進入審核、需求看板回應 — 都會出現在這裡。
        </p>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !notifications || notifications.length === 0 ? (
        <EmptyState
          tone="clear"
          headline="都看完了，沒有未讀通知。"
          sub="當你訂閱的 skill 發布新版本、收到 flag、進入審核時，新通知會即時出現在這裡。bell badge 顯示未讀數量。"
          stats={[
            { value: '0', label: '本週新通知' },
            { value: '0', label: '未讀' },
            { value: '—', label: '上次接收' },
          ]}
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {notifications.map((n, i) => (
            <NotificationRow key={n.id} notif={n} isLast={i === notifications.length - 1} />
          ))}
        </div>
      )}
    </AppShell>
  )
}

function NotificationRow({ notif, isLast }: { notif: Notification; isLast: boolean }) {
  return (
    <div
      className={
        'flex items-start gap-3 px-4 py-3 ' +
        (notif.read ? 'opacity-60 ' : '') +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <CategoryDot category={notif.category} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-[13px] font-medium">{notif.title}</span>
          {!notif.read && <span className="h-1.5 w-1.5 rounded-full bg-[#7F77DD]" aria-label="unread" />}
        </div>
        <p className="mt-0.5 text-[12px] leading-relaxed text-muted-foreground">{notif.body}</p>
      </div>
      <div className="shrink-0 text-[11px] text-muted-foreground">
        {new Date(notif.createdAt).toLocaleDateString('zh-TW')}
      </div>
    </div>
  )
}

function CategoryDot({ category }: { category: Notification['category'] }) {
  const colorMap: Record<Notification['category'], string> = {
    versions: '#7F77DD',  // accent purple — version published
    flags: '#E24B4A',     // danger — flag raised
    reviews: '#EF9F27',   // warning — review needed
    requests: '#378ADD',  // info — request response
  }
  return (
    <Bell
      className="mt-0.5 h-4 w-4 shrink-0"
      style={{ color: colorMap[category] }}
      aria-label={`${category} notification`}
    />
  )
}
