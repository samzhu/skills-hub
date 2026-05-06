import { useState } from 'react'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { CreateRequestModal } from '@/components/CreateRequestModal'
import { VoteButton } from '@/components/VoteButton'
import { RequestActionBar } from '@/components/RequestActionBar'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import { useRequests } from '@/hooks/useRequests'
import { useMe } from '@/hooks/useMe'
import type { SkillRequest } from '@/api/skills'

/**
 * S096g2 — Request Board full feature at `/requests`.
 *
 * 取代 S096g1 read-only stub：CTA 啟用 + 真資料 list（votes desc 預設）+ 樂觀
 * vote toggle + state-aware claim/release/fulfill/delete actions。
 *
 * Sort chips / status filter chips defer per spec §2.4 trim list（AC-15/16/17
 * 不要求；MVP 預設 votes desc + 全 status mix）。
 */
export function RequestBoardPage() {
  const { data: requests, isLoading } = useRequests()
  const { data: me } = useMe()
  const [showModal, setShowModal] = useState(false)

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">需求看板</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">技能需求看板</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          沒找到需要的 skill？發起需求讓社群投票推升優先級，作者認領後實作上架。
        </p>
        <div className="mt-3 flex items-center gap-3">
          {/* S139 lazy gate */}
          <AuthGatedButton
            type="button"
            onClick={() => setShowModal(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-primary/90"
          >
            發起新需求
          </AuthGatedButton>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !requests || requests.length === 0 ? (
        <EmptyState
          tone="invite"
          headline="目前還沒人發起需求。"
          sub="點上方「發起新需求」描述你希望的 skill；社群投票推升優先級，作者認領後實作上架。"
          secondaryAction={{ label: '回去瀏覽現有技能', href: '/browse' }}
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {requests.map((req, i) => (
            <RequestRow
              key={req.id}
              req={req}
              isLast={i === requests.length - 1}
              currentUserId={me?.sub}
            />
          ))}
        </div>
      )}

      {showModal && <CreateRequestModal onClose={() => setShowModal(false)} />}
    </AppShell>
  )
}

function RequestRow({
  req,
  isLast,
  currentUserId,
}: {
  req: SkillRequest
  isLast: boolean
  currentUserId: string | undefined
}) {
  return (
    <div
      className={
        'flex items-center gap-4 px-4 py-3 hover:bg-muted/30 ' +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <VoteButton requestId={req.id} initialCount={req.voteCount} />
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
      <div className="shrink-0">
        <RequestActionBar request={req} currentUserId={currentUserId} />
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
