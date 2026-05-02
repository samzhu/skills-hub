import { Link } from 'react-router'
import { ArrowRight, Upload, ShieldCheck, Boxes, KeySquare } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { BeamFrame } from '@/components/BeamFrame'
import { SkillCard } from '@/components/SkillCard'
import { fetchPublicStats, fetchSkills } from '@/api/skills'

/**
 * S096e1 — Landing page at `/` (unauthenticated entry point).
 *
 * 對齊 prototype `Skills Hub Landing.html` (Engineering Handoff §2.1).
 *
 * 設計要點:
 * - Hero (h1 + sub + 2 CTAs + trust row)
 * - 4 stats band: totalSkills / downloads30d / autoPublishPct / activePublishers
 * - 6 sample skill cards (popular, fetched live)
 * - Compatibility strip — static (5 names + 「+ more」per Engineering Handoff)
 *
 * Auth: 此 route 為 public，不需 OAuth gate（per S027 LAB permitAll）。
 *       Authenticated user（含 `/me` 200 response）日後可選 redirect to `/browse`，
 *       但本 spec 不做 — keep 同 page 給所有 visitor，符合 marketing 入口慣例。
 *
 * Trim from M(12) → S(8): defer Onboarding wizard 至 S096e2; defer hero search bar 至 polish.
 */
export function LandingPage() {
  const { data: stats } = useQuery({
    queryKey: ['public-stats'],
    queryFn: fetchPublicStats,
    staleTime: 5 * 60 * 1000,
  })
  const { data: popularSkills } = useQuery({
    queryKey: ['popular-skills'],
    queryFn: () => fetchSkills({ size: 6, page: 0 }),
    staleTime: 5 * 60 * 1000,
  })

  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* Top minimal nav — only logo + auth CTAs (no main nav links — visitor unauthenticated) */}
      <nav className="flex items-center justify-between border-b border-border px-10 py-4">
        <div className="flex items-center gap-2.5">
          <div className="flex h-7 w-7 items-center justify-center rounded-md bg-foreground text-[12px] font-bold text-background">S</div>
          <span className="text-[14px] font-medium">Skills Hub</span>
        </div>
        <Link to="/browse" className="text-[13px] text-muted-foreground hover:text-foreground">
          瀏覽 →
        </Link>
      </nav>

      {/* Hero */}
      <section className="px-10 py-24 text-center">
        <p className="mb-5 inline-flex items-center gap-2 rounded-full border border-border px-3 py-1 text-[11px] uppercase tracking-wider text-muted-foreground">
          <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-[#1D9E75]" />
          {stats ? `${stats.totalSkills} 個技能 · ${stats.activePublishers} 位發佈者` : 'Skills Hub 技能登錄中心'}
        </p>
        <h1 className="text-[48px] font-medium leading-[1.1] tracking-tight">
          你的團隊真的可以<br />
          信任的技能登錄中心。
        </h1>
        <p className="mx-auto mt-5 max-w-xl text-[16px] leading-relaxed text-muted-foreground">
          在每個 AI 程式助理之間探索、發佈、治理 <strong className="text-foreground">SKILL.md bundle</strong> — 每次上傳都內建 <strong className="text-foreground">自動風險評分</strong>。
        </p>

        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <BeamFrame>
            <Link
              to="/browse"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[13px] font-medium text-primary-foreground"
            >
              瀏覽技能登錄
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </BeamFrame>
          <Link
            to="/publish"
            className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-5 py-2.5 text-[13px] font-medium hover:border-[rgba(255,255,255,0.10)]"
          >
            <Upload className="h-3.5 w-3.5" />
            發佈第一個技能
          </Link>
        </div>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-[12px] text-muted-foreground">
          <span className="flex items-center gap-1.5"><ShieldCheck className="h-3 w-3 text-[#1D9E75]" /> 上傳時自動風險評分</span>
          <span>·</span>
          <span className="flex items-center gap-1.5"><KeySquare className="h-3 w-3" /> 公司 OAuth SSO</span>
          <span>·</span>
          <span className="flex items-center gap-1.5"><Boxes className="h-3 w-3" /> 開放標準 · 無廠商鎖定</span>
        </div>
      </section>

      {/* Stats band */}
      <section className="grid grid-cols-2 divide-x divide-border border-y border-border md:grid-cols-4">
        <StatCell value={stats?.totalSkills ?? '—'} label="已發佈技能" sub={stats ? `跨 ${stats.activePublishers} 位發佈者` : ''} />
        <StatCell value={stats?.downloads30d.toLocaleString() ?? '—'} label="近 30 日下載" sub="滾動視窗" />
        <StatCell value={stats ? `${stats.autoPublishPct}%` : '—'} label="自動上架率（低風險）" sub="首次上傳通過掃描" />
        <StatCell value={stats?.activePublishers ?? '—'} label="活躍發佈者" sub="跨整個組織" />
      </section>

      {/* Popular skills preview */}
      <section className="mx-auto max-w-6xl px-10 py-16">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">登錄中即時</p>
        <h2 className="mt-2 text-[28px] font-medium tracking-tight">你的團隊今天就在用的技能</h2>
        <p className="mt-2 max-w-xl text-[14px] leading-relaxed text-muted-foreground">
          每個技能都自動掃描。低風險立即上架；高風險進入人工審核隊列。
        </p>
        <div className="mt-8 grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          {(popularSkills?.content ?? []).slice(0, 6).map((s, i) => (
            <SkillCard key={s.id} skill={s} featured={i === 0} />
          ))}
        </div>
      </section>

      {/* Compatibility strip */}
      <section className="border-t border-border px-10 py-8">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-8 gap-y-2 text-[13px] text-muted-foreground">
          <span className="text-[11px] font-semibold uppercase tracking-wider">支援工具</span>
          <span>Claude Code</span>
          <span>Cursor</span>
          <span>Gemini CLI</span>
          <span>GitHub Copilot</span>
          <span>Cline</span>
          <span className="text-[#A8A49C]">+ 更多 · agentskills.io 開放標準</span>
        </div>
      </section>

      {/* Final CTA */}
      <section className="px-10 py-20 text-center">
        <h2 className="text-[36px] font-medium tracking-tight">像分享套件一樣分享技能。</h2>
        <p className="mx-auto mt-3 max-w-md text-[14px] leading-relaxed text-muted-foreground">
          你已經在分享元件、設定檔、輔助函式 — 技能也是同樣的東西，只是打包給 AI agent 用。
        </p>
        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          <BeamFrame>
            <Link
              to="/browse"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[13px] font-medium text-primary-foreground"
            >
              瀏覽技能登錄
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </BeamFrame>
          <Link
            to="/publish"
            className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-5 py-2.5 text-[13px] font-medium"
          >
            發佈技能
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-border px-10 py-6">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 text-[12px] text-muted-foreground">
          <span>Skills Hub · SKILL.md bundle 企業技能登錄中心</span>
          <div className="flex gap-5">
            <Link to="/docs/your-first-skill" className="hover:text-foreground">文件</Link>
            <a href="/v3/api-docs" className="hover:text-foreground">API</a>
            <Link to="/" className="hover:text-foreground">狀態</Link>
          </div>
        </div>
      </footer>
    </div>
  )
}

function StatCell({ value, label, sub }: { value: string | number; label: string; sub?: string }) {
  return (
    <div className="px-6 py-8 text-center md:px-10">
      <p className="font-mono text-[28px] font-medium tabular-nums text-foreground">{value}</p>
      <p className="mt-1 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</p>
      {sub && <p className="mt-1 text-[11px] text-muted-foreground">{sub}</p>}
    </div>
  )
}
