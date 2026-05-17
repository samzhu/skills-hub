import { useState } from 'react'
import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, BellOff, Eye, MoreVertical, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { AppShell } from '@/components/AppShell'
import { MetricCard } from '@/components/MetricCard'
import { IconTile } from '@/components/IconTile'
import { RiskBadge } from '@/components/RiskBadge'
import { BeamFrame } from '@/components/BeamFrame'
import { EmptyState } from '@/components/EmptyState'
import { Sparkline } from '@/components/Sparkline'
import { deleteSkill } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'
import { getDisplayName } from '@/lib/displayName'
import { useMe } from '@/hooks/useMe'
import { useAuth } from '@/hooks/useAuth'
import { useSkillList } from '@/hooks/useSkillList'
import { useSkillStats } from '@/hooks/useSkillStats'
import { useFlagsSummary } from '@/hooks/useFlagsSummary'
import { useMySubscriptionDetails, useUnsubscribeSkill } from '@/hooks/useSubscription'
import type { SubscriptionSummary } from '@/api/subscriptions'
import type { Skill } from '@/types/skill'

/**
 * S094a — `/my-skills` Author Dashboard.
 *
 * 對齊 docs/grimo/ui/prototype/my_skills_author_dashboard.html (README ll.69-87).
 *
 * 設計重點:
 * - Hero: author identity hook (P6 SBE「作者查看自己的數據」)
 * - 4 MetricCards: Total skills (status breakdown) / Total downloads / Avg rating（MVP placeholder）/ Open flags
 * - Tabs by lifecycle status (All / Published / Drafts / Suspended)
 * - Table-style row list with IconTile + name + status pill + downloads + version
 * - 0 skills → EmptyState invite tone (S094c reuse)
 *
 * 範圍 trim (vs prototype 完整版):
 * - Sparkline column 暫缺（per-skill 30d trend endpoint 留 follow-up polish）
 * - Avg rating 顯 "—"（rating 系統 MVP 未實作）
 *
 * Auth: LAB mode 用 /api/v1/me 取 sub 為 author identity；無 OAuth gate，
 * 任何 user 可改 URL 看別人的 dashboard（已知 MVP 限制 per Feature First）
 */
export function MySkillsPage() {
  const queryClient = useQueryClient()
  const { data: me, isLoading: meLoading, isError: meError } = useMe()
  const auth = useAuth()
  // S154b — 用 platform userId 作 author filter（S154 backend `skills.author` 已切 user_id；
  // 過去用 me.sub 是 S094a 寫的，未對齊 S154 ship 後的 schema）。
  const author = me?.userId
  // S132: page-level auth gate 在 useSkillList 之後 render 路徑早 return；query 仍會跑但 result
  // 不被使用（page 顯 EmptyState 時 skillsPage 不參與 render）— 對齊 React Hook 順序固定原則
  const { data: skillsPage, isLoading: skillsLoading } = useSkillList({
    author,
    size: 100,
  })
  // S112-T04: 待處理回報 — me 已 loaded 才查（避免 anonymous 拒接）；放早 return 前以對齊 Rules of Hooks
  const { data: flagsSummary } = useFlagsSummary(!!author)
  const { data: subscriptionDetails, isLoading: subscriptionsLoading } = useMySubscriptionDetails()
  const unsubscribeSkill = useUnsubscribeSkill()
  const [tab, setTab] = useState<'all' | 'PUBLISHED' | 'DRAFT' | 'SUSPENDED' | 'SUBSCRIPTIONS'>('all')
  const [pendingDelete, setPendingDelete] = useState<Skill | null>(null)
  const deleteMutation = useMutation({
    mutationFn: (skillId: string) => deleteSkill(skillId),
    onSuccess: (_, skillId) => {
      queryClient.setQueriesData({ queryKey: ['skills', 'list'] }, (old: unknown) => {
        if (!old || typeof old !== 'object' || !('content' in old)) return old
        const page = old as { content: Skill[]; page?: unknown }
        return { ...page, content: page.content.filter((skill) => skill.id !== skillId) }
      })
      setPendingDelete(null)
      toast.success('技能已刪除')
    },
    onError: (err) => {
      toast.error(`刪除失敗：${localizeApiError(err)}`)
    },
  })

  if (meLoading || (author && skillsLoading)) {
    return (
      <AppShell>
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      </AppShell>
    )
  }

  // S132 (Mode B Round 41 follow-up; S130 regression fix): 未登入 → 顯空狀態 + 登入提示
  // S130 v3.10.9 ship 後 OAuth=true 模式下 /me require auth；anonymous 走 useMe 失敗 →
  // me=undefined → author=undefined。修補前頁面誤顯「以 身份發布」(空白) + 全 PUBLIC skills 為
  // 「你的 N 個技能」。修補：page-level auth gate 走 EmptyState invite tone (S094c reuse)。
  if (!author || meError) {
    // S139：anonymous CTA — 既有 EmptyState invite tone 補一顆登入按鈕（lazy gate per AC-1 公開瀏覽）
    return (
      <AppShell>
        <EmptyState
          tone="invite"
          headline="請先登入後查看自己發布的技能"
          sub="此頁顯示你以發佈者身份建立的技能、下載統計與待處理回報。登入後將自動載入你的資料。"
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

  const allSkills = skillsPage?.content ?? []
  const filteredSkills = tab === 'all' ? allSkills : allSkills.filter((s) => s.status === tab)
  const subscriptionRows = subscriptionDetails ?? []
  const unsubscribeFromList = (skillId: string) => {
    unsubscribeSkill.mutate(skillId, {
      onSuccess: () => {
        queryClient.setQueryData<SubscriptionSummary[]>(['my-subscriptions', 'details'], (old) =>
          old?.filter((s) => s.skillId !== skillId) ?? old,
        )
        queryClient.setQueryData<string[]>(['my-subscriptions'], (old) =>
          old?.filter((id) => id !== skillId) ?? old,
        )
        toast.success('已取消訂閱')
      },
      onError: (err) => {
        toast.error(`取消訂閱失敗：${localizeApiError(err)}`)
      },
    })
  }

  // Metric calculations
  const total = allSkills.length
  const published = allSkills.filter((s) => s.status === 'PUBLISHED').length
  const drafts = allSkills.filter((s) => s.status === 'DRAFT').length
  const suspended = allSkills.filter((s) => s.status === 'SUSPENDED').length
  const totalDownloads = allSkills.reduce((sum, s) => sum + (s.downloadCount ?? 0), 0)

  return (
    <AppShell>
      {/* Hero */}
      <div className="mb-6">
        <p className="text-[12px] text-muted-foreground">
          以 <span className="font-mono text-foreground">{me?.name ?? me?.email ?? me?.handle ?? author}</span> 身份發布
        </p>
        <div className="mt-1 flex items-end justify-between gap-4">
          <h1 className="text-[22px] font-semibold tracking-tight">
            你的 {total} 個技能
          </h1>
          <BeamFrame>
            <Link
              to="/publish"
              className="inline-flex items-center gap-1.5 rounded-md bg-[#181818] px-4 py-2 text-[13px] font-medium text-white"
            >
              發布新技能
              <ArrowRight className="h-3 w-3" />
            </Link>
          </BeamFrame>
        </div>
      </div>

      {/* S112-T04: 3-card grid — 移除「平均評分」（等 S101a Quality Score）；接 useFlagsSummary 真資料 */}
      <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <MetricCard
          label="技能總數"
          value={total}
          subtitle={`已發布 ${published} · 草稿 ${drafts} · 已停用 ${suspended}`}
        />
        <MetricCard
          label="下載總數"
          value={totalDownloads.toLocaleString()}
          subtitle="累積下載"
        />
        <MetricCard
          label="待處理回報"
          value={flagsSummary?.openCount ?? 0}
          subtitle="未處理 OPEN 狀態"
        />
      </div>

      {/* Tabs */}
      <div
        data-testid="my-skills-lifecycle-tabs"
        className="mb-3 inline-flex max-w-full flex-wrap gap-1 rounded-md border border-border bg-card p-1"
      >
        <TabPill id="all" label="全部" count={total} active={tab === 'all'} onClick={() => setTab('all')} />
        <TabPill id="PUBLISHED" label="已發布" count={published} active={tab === 'PUBLISHED'} onClick={() => setTab('PUBLISHED')} />
        <TabPill id="DRAFT" label="草稿" count={drafts} active={tab === 'DRAFT'} onClick={() => setTab('DRAFT')} />
        <TabPill id="SUSPENDED" label="已停用" count={suspended} active={tab === 'SUSPENDED'} onClick={() => setTab('SUSPENDED')} />
        <TabPill id="SUBSCRIPTIONS" label="訂閱" count={subscriptionRows.length} active={tab === 'SUBSCRIPTIONS'} onClick={() => setTab('SUBSCRIPTIONS')} />
      </div>

      {tab === 'SUBSCRIPTIONS' ? (
        <SubscriptionList
          rows={subscriptionRows}
          isLoading={subscriptionsLoading}
          isUnsubscribing={unsubscribeSkill.isPending}
          onUnsubscribe={unsubscribeFromList}
        />
      ) : total === 0 ? (
        <EmptyState
          tone="invite"
          headline="你還沒有發布過技能。"
          sub="把你的工作流程打包成 SKILL.md bundle 上傳。完整 round-trip（上傳 → 自動掃描 → 發布）通常少於 1 分鐘。"
          // S105: 新作者 onboarding context — opt-in 顯示 publish flow 4-step strip
          // 其他 invite tone callsites (Reviews/Collections/Requests/Search) 不傳此 prop，default 隱藏
          steps={['打包', '自動掃描', '發佈', '追蹤']}
          primaryAction={{ label: '發布你的第一個技能', href: '/publish' }}
          secondaryAction={{ label: '看 Docs walkthrough', href: '/docs/your-first-skill' }}
        />
      ) : (
        <>
          <div className="overflow-hidden rounded-lg border border-border bg-card">
            {filteredSkills.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-muted-foreground">
                此分類無技能
              </div>
            ) : (
              filteredSkills.map((skill, i) => (
                <SkillRow
                  key={skill.id}
                  skill={skill}
                  isLast={i === filteredSkills.length - 1}
                  onDelete={() => setPendingDelete(skill)}
                />
              ))
            )}
          </div>
        </>
      )}
      {pendingDelete && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-4" role="presentation">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-skill-title"
            className="w-full max-w-sm rounded-lg border border-border bg-card p-5 shadow-xl"
          >
            <h2 id="delete-skill-title" className="text-[16px] font-semibold">
              確定要刪除「{pendingDelete.name}」嗎？
            </h2>
            <p className="mt-2 text-[13px] leading-6 text-muted-foreground">
              此操作會移除技能、版本、分享權限與相關統計資料；刪除後無法從頁面復原。
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setPendingDelete(null)}
                className="h-9 rounded-md border border-border px-4 text-[13px] hover:bg-muted/50"
                disabled={deleteMutation.isPending}
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => deleteMutation.mutate(pendingDelete.id)}
                className="inline-flex h-9 items-center gap-1.5 rounded-md bg-[#B42318] px-4 text-[13px] font-medium text-white hover:bg-[#912018] disabled:opacity-70"
                disabled={deleteMutation.isPending}
              >
                <Trash2 className="h-3.5 w-3.5" />
                確認刪除
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  )
}

function SubscriptionList({
  rows,
  isLoading,
  isUnsubscribing,
  onUnsubscribe,
}: {
  rows: SubscriptionSummary[]
  isLoading: boolean
  isUnsubscribing: boolean
  onUnsubscribe: (skillId: string) => void
}) {
  if (isLoading) {
    return <div className="py-8 text-center text-sm text-muted-foreground">載入訂閱中...</div>
  }
  if (rows.length === 0) {
    return (
      <EmptyState
        tone="invite"
        headline="尚未訂閱任何技能"
        sub="前往瀏覽找到有興趣的技能後點「訂閱」，未來有新版本時會收到通知。"
        primaryAction={{ label: '前往瀏覽', href: '/' }}
      />
    )
  }
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card">
      {rows.map((row, i) => (
        <SubscriptionRow
          key={row.skillId}
          row={row}
          isLast={i === rows.length - 1}
          isPending={isUnsubscribing}
          onUnsubscribe={() => onUnsubscribe(row.skillId)}
        />
      ))}
    </div>
  )
}

function SubscriptionRow({
  row,
  isLast,
  isPending,
  onUnsubscribe,
}: {
  row: SubscriptionSummary
  isLast: boolean
  isPending: boolean
  onUnsubscribe: () => void
}) {
  return (
    <div className={'flex items-center gap-3 px-4 py-3 hover:bg-muted/30 ' + (isLast ? '' : 'border-b border-border')}>
      <IconTile name={row.skillName} category="Subscription" size="sm" />
      <Link to={`/skills/${row.skillId}`} className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-[13px] font-medium">{row.skillName}</span>
          <RiskBadge level={row.riskLevel} />
          <span className="rounded-sm bg-[#171719] px-2 py-0.5 font-mono text-[11px]">
            v{row.latestVersion ?? '—'}
          </span>
        </div>
        <p className="mt-0.5 truncate text-[11.5px] text-muted-foreground">
          <span>{getDisplayName({ author: row.author, authorDisplayName: row.authorDisplayName })}</span>
          <span> · 訂閱於 {row.subscribedAt.slice(0, 10)}</span>
        </p>
      </Link>
      <button
        type="button"
        aria-label={`取消訂閱 ${row.skillName}`}
        onClick={onUnsubscribe}
        disabled={isPending}
        className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border border-border px-3 text-[12px] text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-60"
      >
        <BellOff className="h-3.5 w-3.5" />
        取消訂閱
      </button>
    </div>
  )
}

function TabPill({
  id,
  label,
  count,
  active,
  onClick,
}: {
  id: string
  label: string
  count: number
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        active
          ? 'rounded-md border border-accent/30 bg-accent-soft px-3 py-1.5 text-[12px] font-medium text-accent-text focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'
          : 'rounded-md border border-transparent bg-transparent px-3 py-1.5 text-[12px] text-muted-foreground hover:bg-muted/40 hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'
      }
    >
      <span>{label}</span>
      <span data-testid={`my-skills-tab-count-${id}`} className="ml-1 font-mono text-muted-foreground">
        {count}
      </span>
    </button>
  )
}

function SkillRow({ skill, isLast, onDelete }: { skill: Skill; isLast: boolean; onDelete: () => void }) {
  // S096d3: 30d sparkline data；只 PUBLISHED skill 拉（DRAFT/SUSPENDED 沒下載資料）
  const { data: trend } = useSkillStats(skill.status === 'PUBLISHED' ? skill.id : undefined, '30d')
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <div
      className={
        'relative flex items-center gap-3 px-4 py-3 hover:bg-muted/30 ' +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <IconTile name={skill.name} category={skill.category} size="sm" />
      <Link to={`/skills/${skill.id}`} className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-[13px] font-medium">{skill.name}</span>
          <StatusPill status={skill.status} />
          <RiskBadge level={skill.riskLevel} />
        </div>
        <p className="mt-0.5 truncate text-[11.5px] text-muted-foreground">{skill.description}</p>
      </Link>
      {/* S096d3: 30d sparkline column (per prototype my_skills_author_dashboard.html) */}
      <div className="hidden shrink-0 sm:block" aria-label="30d download trend">
        {trend && <Sparkline data={trend} width={64} height={20} />}
      </div>
      <div className="flex shrink-0 flex-col items-end text-right">
        <span className="font-mono text-[12px] tabular-nums">
          {(skill.downloadCount ?? 0).toLocaleString()}
        </span>
        <span className="text-[10px] text-muted-foreground">次下載</span>
      </div>
      <div className="shrink-0 text-right">
        <span className="rounded-sm bg-[#171719] px-2 py-0.5 font-mono text-[11px]">
          v{skill.latestVersion ?? '—'}
        </span>
      </div>
      <div className="relative shrink-0">
        <button
          type="button"
          aria-label={`${skill.name}的動作`}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
          className="flex h-8 w-8 items-center justify-center rounded-md border border-border text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <MoreVertical className="h-4 w-4" />
        </button>
        {menuOpen && (
          <div
            role="menu"
            className="absolute right-0 top-9 z-20 min-w-28 rounded-md border border-border bg-card p-1 shadow-lg"
          >
            <Link
              to={`/skills/${skill.id}`}
              role="menuitem"
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-[13px] text-foreground hover:bg-muted"
            >
              <Eye className="h-3.5 w-3.5" />
              檢視
            </Link>
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setMenuOpen(false)
                onDelete()
              }}
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-[13px] text-[#B42318] hover:bg-muted"
            >
              <Trash2 className="h-3.5 w-3.5" />
              刪除
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  // S094a: status semantic pill aligned with DESIGN.md 4-tier
  // PUBLISHED → success-soft / DRAFT → warning-soft / SUSPENDED → danger-soft
  const styles: Record<string, { bg: string; fg: string; label: string }> = {
    PUBLISHED: { bg: 'rgba(29,158,117,0.14)', fg: '#6FD8B0', label: '已發布' },
    DRAFT: { bg: 'rgba(239,159,39,0.14)', fg: '#FAC775', label: '草稿' },
    SUSPENDED: { bg: 'rgba(226,75,74,0.14)', fg: '#F2A6A6', label: '已停用' },
  }
  const s = styles[status] ?? { bg: '#171719', fg: '#A8A49C', label: status }
  return (
    <span
      className="inline-block rounded px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider"
      style={{ backgroundColor: s.bg, color: s.fg }}
    >
      {s.label}
    </span>
  )
}
