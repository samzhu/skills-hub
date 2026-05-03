import { useQuery } from '@tanstack/react-query'
import { ArrowUp } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { fetchRequests, type SkillRequest } from '@/api/skills'

/**
 * S096g1 — Request Board read-only stub at `/requests`.
 *
 * 對齊 PRD §P8 + Engineering Handoff §2.13. 本 spec ship 為 read-only：
 * - GET /api/v1/requests → list（backend stub returns []）
 * - 顯 row list with title / description / vote count / status pill
 * - 0 results → EmptyState invite tone「目前還沒人發起需求」
 *
 * Defer S096g2: voting / claim / new request submit / RequestPosted domain
 * event / RequestVoted / RequestFulfilled events.
 *
 * UX：disabled 「發起新需求」 CTA 暗示 feature 未啟用 — 對齊 Engineering Handoff
 * §10「Disable, don't hide, blocked actions」原則。
 */
export function RequestBoardPage() {
  const { data: requests, isLoading } = useQuery<SkillRequest[]>({
    queryKey: ['requests'],
    queryFn: fetchRequests,
    staleTime: 60 * 1000,
  })

  return (
    <AppShell>
      {/* Hero */}
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">需求看板</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">技能需求看板</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          沒找到需要的 skill？發起需求讓社群投票推升優先級，作者認領後實作上架。
        </p>
        <div className="mt-3 flex items-center gap-3">
          <button
            type="button"
            disabled
            title="即將開放 — 投票與認領功能後續版本推出"
            className="inline-flex cursor-not-allowed items-center gap-1.5 rounded-md border border-border bg-card px-4 py-2 text-[13px] font-medium opacity-50"
          >
            發起新需求（即將開放）
          </button>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !requests || requests.length === 0 ? (
        <EmptyState
          tone="invite"
          headline="目前還沒人發起需求。"
          sub="後續版本推出後，可以從這裡發起「我希望某種 skill 存在」的需求；社群投票推升優先級，作者認領後實作。"
          secondaryAction={{ label: '回去瀏覽現有技能', href: '/browse' }}
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {requests.map((req, i) => (
            <RequestRow key={req.id} req={req} isLast={i === requests.length - 1} />
          ))}
        </div>
      )}
    </AppShell>
  )
}

function RequestRow({ req, isLast }: { req: SkillRequest; isLast: boolean }) {
  return (
    <div
      className={
        'flex items-center gap-4 px-4 py-3 hover:bg-muted/30 ' +
        (isLast ? '' : 'border-b border-border')
      }
    >
      {/* Vote column */}
      <div className="flex shrink-0 flex-col items-center">
        <ArrowUp className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="font-mono text-[13px] font-medium tabular-nums">{req.voteCount}</span>
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-[14px] font-medium">{req.title}</span>
          <StatusPill status={req.status} />
        </div>
        <p className="mt-0.5 truncate text-[12px] text-muted-foreground">{req.description}</p>
      </div>
      <div className="shrink-0 text-[11px] text-muted-foreground">
        {new Date(req.createdAt).toLocaleDateString('zh-TW')}
      </div>
    </div>
  )
}

function StatusPill({ status }: { status: SkillRequest['status'] }) {
  const styles: Record<SkillRequest['status'], { bg: string; fg: string; label: string }> = {
    OPEN: { bg: 'rgba(55,138,221,0.14)', fg: '#B0D5F2', label: '開放中' },
    IN_PROGRESS: { bg: 'rgba(239,159,39,0.14)', fg: '#FAC775', label: '實作中' },
    FULFILLED: { bg: 'rgba(29,158,117,0.14)', fg: '#6FD8B0', label: '已完成' },
  }
  const s = styles[status]
  return (
    <span
      className="inline-block rounded px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider"
      style={{ backgroundColor: s.bg, color: s.fg }}
    >
      {s.label}
    </span>
  )
}
