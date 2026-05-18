import { useState } from 'react'
import { Link } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { RequestCreatePanel } from '@/components/RequestCreatePanel'
import { VoteButton } from '@/components/VoteButton'
import { useRequests } from '@/hooks/useRequests'
import type { SkillRequest } from '@/api/skills'

/**
 * S196-T01 — Request Board browse shell.
 * Two primary tabs only: browse demand or create a new demand inline.
 */
export function RequestBoardPage() {
  const [activeTab, setActiveTab] = useState<RequestBoardTab>('browse')
  const [sort, setSort] = useState<RequestSortMode>('votes')
  const { data: requests, isLoading } = useRequests({ sort })
  const requestCount = requests?.length ?? 0

  const browseTabId = 'request-board-tab-browse'
  const createTabId = 'request-board-tab-create'
  const browsePanelId = 'request-board-panel-browse'
  const createPanelId = 'request-board-panel-create'

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">需求看板</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">只要有人需要，就會有勇者出現。</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          先瀏覽大家缺哪些 skill，再把自己的工作問題開成新需求。
        </p>

        <div
          role="tablist"
          aria-label="需求看板模式"
          className="mt-4 inline-flex rounded-lg border border-border bg-card p-1"
        >
          <RequestTabButton
            id={browseTabId}
            panelId={browsePanelId}
            selected={activeTab === 'browse'}
            onClick={() => setActiveTab('browse')}
          >
            瀏覽需求
            {requestCount > 0 && <span className="font-mono text-[11px]">{requestCount}</span>}
          </RequestTabButton>
          <RequestTabButton
            id={createTabId}
            panelId={createPanelId}
            selected={activeTab === 'create'}
            onClick={() => setActiveTab('create')}
          >
            我要開需求
          </RequestTabButton>
        </div>
      </div>

      {activeTab === 'browse' ? (
        <section
          id={browsePanelId}
          role="tabpanel"
          aria-labelledby={browseTabId}
          tabIndex={0}
        >
          <RequestBrowsePanel
            isLoading={isLoading}
            requests={requests ?? []}
            sort={sort}
            onSortChange={setSort}
            onCreateRequest={() => setActiveTab('create')}
          />
        </section>
      ) : (
        <section
          id={createPanelId}
          role="tabpanel"
          aria-labelledby={createTabId}
          tabIndex={0}
        >
          <RequestCreatePanel onCreated={() => setActiveTab('browse')} />
        </section>
      )}
    </AppShell>
  )
}

type RequestBoardTab = 'browse' | 'create'
type RequestSortMode = 'votes' | 'created'

function RequestTabButton({
  id,
  panelId,
  selected,
  onClick,
  children,
}: {
  id: string
  panelId: string
  selected: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      id={id}
      type="button"
      role="tab"
      aria-selected={selected}
      aria-controls={panelId}
      tabIndex={selected ? 0 : -1}
      onClick={onClick}
      className={
        'inline-flex items-center gap-2 rounded-md px-3 py-1.5 text-[13px] font-medium transition ' +
        (selected ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground')
      }
    >
      {children}
    </button>
  )
}

function RequestBrowsePanel({
  isLoading,
  requests,
  sort,
  onSortChange,
  onCreateRequest,
}: {
  isLoading: boolean
  requests: SkillRequest[]
  sort: RequestSortMode
  onSortChange: (sort: RequestSortMode) => void
  onCreateRequest: () => void
}) {
  if (isLoading) {
    return <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
  }

  if (requests.length === 0) {
    return (
      <EmptyState
        tone="invite"
        headline="目前還沒人發起需求。"
        sub="點「我要開需求」描述你希望的 skill；社群投票表達「我也要」，作者看到票數高的需求自然會考慮實作。"
        primaryAction={{ label: '我要開需求', onClick: onCreateRequest }}
      />
    )
  }

  const rankedRequests = [...requests].sort((a, b) => b.voteCount - a.voteCount)

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-[12px] text-muted-foreground">社群用投票把最需要的 skill 往前推。</p>
          <div className="inline-flex rounded-md border border-border bg-card p-1" aria-label="需求排序">
            <SortButton active={sort === 'votes'} onClick={() => onSortChange('votes')}>
              票數最高
            </SortButton>
            <SortButton active={sort === 'created'} onClick={() => onSortChange('created')}>
              最新
            </SortButton>
          </div>
        </div>
        <div className="grid gap-3">
          {requests.map((req) => (
            <RequestCard key={req.id} req={req} />
          ))}
        </div>
      </div>

      <aside className="rounded-lg border border-border bg-card p-4">
        <h2 className="text-[14px] font-semibold">需求排行榜</h2>
        <ol aria-label="需求排行榜" className="mt-3 space-y-2">
          {rankedRequests.map((req, index) => (
            <li key={req.id} className="flex items-start gap-2 text-[12px]">
              <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded bg-muted font-mono text-[10px] text-muted-foreground">
                {index + 1}
              </span>
              <span className="min-w-0 flex-1 truncate font-medium">{req.title}</span>
              <span className="font-mono text-muted-foreground">{req.voteCount}</span>
            </li>
          ))}
        </ol>
      </aside>
    </div>
  )
}

function SortButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        'rounded px-3 py-1 text-[12px] font-medium transition ' +
        (active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground')
      }
    >
      {children}
    </button>
  )
}

function RequestCard({ req }: { req: SkillRequest }) {
  return (
    <div
      className="grid gap-4 rounded-lg border border-border bg-card p-4 hover:bg-muted/20 sm:grid-cols-[auto_minmax(0,1fr)_auto]"
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
