import { useState } from 'react'
import { Link } from 'react-router'
import { ArrowRight } from 'lucide-react'
import { AppShell } from '@/components/AppShell'
import { MetricCard } from '@/components/MetricCard'
import { IconTile } from '@/components/IconTile'
import { RiskBadge } from '@/components/RiskBadge'
import { BeamFrame } from '@/components/BeamFrame'
import { EmptyState } from '@/components/EmptyState'
import { Sparkline } from '@/components/Sparkline'
import { useMe } from '@/hooks/useMe'
import { useSkillList } from '@/hooks/useSkillList'
import { useSkillStats } from '@/hooks/useSkillStats'
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
  const { data: me, isLoading: meLoading } = useMe()
  const author = me?.sub
  const { data: skillsPage, isLoading: skillsLoading } = useSkillList({
    author,
    size: 200, // dashboard 全顯，避免分頁干擾
  })
  const [tab, setTab] = useState<'all' | 'PUBLISHED' | 'DRAFT' | 'SUSPENDED'>('all')

  if (meLoading || skillsLoading) {
    return (
      <AppShell>
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      </AppShell>
    )
  }

  const allSkills = skillsPage?.content ?? []
  const filteredSkills = tab === 'all' ? allSkills : allSkills.filter((s) => s.status === tab)

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
          以 <span className="font-mono text-foreground">{author}</span> 身份發布
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

      {/* 4 metrics */}
      <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
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
        <MetricCard label="平均評分" value="—" subtitle="評分系統未啟用" />
        <MetricCard label="待處理回報" value={0} subtitle="MVP 暫缺" />
      </div>

      {/* Empty state when 0 skills (use S094c invite tone) */}
      {total === 0 ? (
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
          {/* Tabs */}
          <div className="mb-3 flex flex-wrap gap-2">
            <TabPill active={tab === 'all'} onClick={() => setTab('all')}>
              全部 ({total})
            </TabPill>
            <TabPill active={tab === 'PUBLISHED'} onClick={() => setTab('PUBLISHED')}>
              已發布 ({published})
            </TabPill>
            <TabPill active={tab === 'DRAFT'} onClick={() => setTab('DRAFT')}>
              草稿 ({drafts})
            </TabPill>
            <TabPill active={tab === 'SUSPENDED'} onClick={() => setTab('SUSPENDED')}>
              已停用 ({suspended})
            </TabPill>
          </div>

          {/* Skill list — table-style rows */}
          <div className="overflow-hidden rounded-lg border border-border bg-card">
            {filteredSkills.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-muted-foreground">
                此分類無技能
              </div>
            ) : (
              filteredSkills.map((skill, i) => (
                <SkillRow key={skill.id} skill={skill} isLast={i === filteredSkills.length - 1} />
              ))
            )}
          </div>
        </>
      )}
    </AppShell>
  )
}

function TabPill({
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
      className={
        active
          ? 'rounded-md bg-[#181818] px-3 py-1.5 text-[12px] font-medium text-white'
          : 'rounded-md border border-border bg-white px-3 py-1.5 text-[12px] text-foreground hover:bg-muted/40'
      }
    >
      {children}
    </button>
  )
}

function SkillRow({ skill, isLast }: { skill: Skill; isLast: boolean }) {
  // S096d3: 30d sparkline data；只 PUBLISHED skill 拉（DRAFT/SUSPENDED 沒下載資料）
  const { data: trend } = useSkillStats(skill.status === 'PUBLISHED' ? skill.id : undefined, '30d')
  return (
    <Link
      to={`/skills/${skill.id}`}
      className={
        'flex items-center gap-3 px-4 py-3 hover:bg-muted/30 ' +
        (isLast ? '' : 'border-b border-border')
      }
    >
      <IconTile name={skill.name} category={skill.category} size="sm" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-[13px] font-medium">{skill.name}</span>
          <StatusPill status={skill.status} />
          <RiskBadge level={skill.riskLevel} />
        </div>
        <p className="mt-0.5 truncate text-[11.5px] text-muted-foreground">{skill.description}</p>
      </div>
      {/* S096d3: 30d sparkline column (per prototype my_skills_author_dashboard.html) */}
      <div className="hidden shrink-0 sm:block" aria-label="30d download trend">
        {trend && <Sparkline data={trend} width={64} height={20} />}
      </div>
      <div className="flex shrink-0 flex-col items-end text-right">
        <span className="font-mono text-[12px] tabular-nums">
          {(skill.downloadCount ?? 0).toLocaleString()}
        </span>
        <span className="text-[10px] text-muted-foreground">downloads</span>
      </div>
      <div className="shrink-0 text-right">
        <span className="rounded-sm bg-[#171719] px-2 py-0.5 font-mono text-[11px]">
          v{skill.latestVersion ?? '—'}
        </span>
      </div>
    </Link>
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
