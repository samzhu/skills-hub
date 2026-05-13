import { useState } from 'react'
import { Link } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { CreateRequestModal } from '@/components/CreateRequestModal'
import { VoteButton } from '@/components/VoteButton'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import { useRequests } from '@/hooks/useRequests'
import type { SkillRequest } from '@/api/skills'

/**
 * S096g2 → S156c — Request Board at `/requests`（voting-board pivot）。
 *
 * 移除 claim/release/fulfill 流程：card 不再顯 status pill / ActionBar；title 改成
 * <Link to="/requests/:id"> 跳 detail page（T04 加 route）。剩餘互動：vote toggle + 發起新需求。
 *
 * Sort chips defer per spec §2.4 trim list；MVP 預設 votes desc。
 */
export function RequestBoardPage() {
  const { data: requests, isLoading } = useRequests()
  const [showModal, setShowModal] = useState(false)

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">需求看板</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">技能需求看板</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          沒找到需要的 skill？發起需求讓社群投票表達「我也要」— 票數高的需求作者會看到並決定是否實作。
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
          sub="點上方「發起新需求」描述你希望的 skill；社群投票表達「我也要」，作者看到票數高的需求自然會考慮實作。"
          secondaryAction={{ label: '回去瀏覽現有技能', href: '/browse' }}
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {requests.map((req, i) => (
            <RequestRow key={req.id} req={req} isLast={i === requests.length - 1} />
          ))}
        </div>
      )}

      {showModal && <CreateRequestModal onClose={() => setShowModal(false)} />}
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
      <VoteButton requestId={req.id} initialCount={req.voteCount} />
      <div className="min-w-0 flex-1">
        <Link
          to={`/requests/${req.id}`}
          className="block truncate text-[14px] font-medium hover:underline"
        >
          {req.title}
        </Link>
        <p className="mt-0.5 truncate text-[12px] text-muted-foreground">{req.description}</p>
      </div>
      <div className="shrink-0 text-[11px] text-muted-foreground">
        {new Date(req.createdAt).toLocaleDateString('zh-TW')}
      </div>
    </div>
  )
}
