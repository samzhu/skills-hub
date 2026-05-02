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
          Browse →
        </Link>
      </nav>

      {/* Hero */}
      <section className="px-10 py-24 text-center">
        <p className="mb-5 inline-flex items-center gap-2 rounded-full border border-border px-3 py-1 text-[11px] uppercase tracking-wider text-muted-foreground">
          <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-[#1D9E75]" />
          {stats ? `${stats.totalSkills} skills · ${stats.activePublishers} publishers` : 'Skills Hub registry'}
        </p>
        <h1 className="text-[48px] font-medium leading-[1.1] tracking-tight">
          The skills registry your<br />
          team can actually trust.
        </h1>
        <p className="mx-auto mt-5 max-w-xl text-[16px] leading-relaxed text-muted-foreground">
          Discover, publish, and govern <strong className="text-foreground">SKILL.md bundles</strong> across every AI coding assistant — with <strong className="text-foreground">automatic risk scoring</strong> baked into every upload.
        </p>

        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <BeamFrame>
            <Link
              to="/browse"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[13px] font-medium text-primary-foreground"
            >
              Browse the registry
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </BeamFrame>
          <Link
            to="/publish"
            className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-5 py-2.5 text-[13px] font-medium hover:border-[rgba(255,255,255,0.10)]"
          >
            <Upload className="h-3.5 w-3.5" />
            Publish your first skill
          </Link>
        </div>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-[12px] text-muted-foreground">
          <span className="flex items-center gap-1.5"><ShieldCheck className="h-3 w-3 text-[#1D9E75]" /> Auto risk-scored on upload</span>
          <span>·</span>
          <span className="flex items-center gap-1.5"><KeySquare className="h-3 w-3" /> SSO via company OAuth</span>
          <span>·</span>
          <span className="flex items-center gap-1.5"><Boxes className="h-3 w-3" /> Open standard · no lock-in</span>
        </div>
      </section>

      {/* Stats band */}
      <section className="grid grid-cols-2 divide-x divide-border border-y border-border md:grid-cols-4">
        <StatCell value={stats?.totalSkills ?? '—'} label="Skills published" sub={stats ? `across ${stats.activePublishers} publishers` : ''} />
        <StatCell value={stats?.downloads30d.toLocaleString() ?? '—'} label="Downloads · 30 days" sub="rolling window" />
        <StatCell value={stats ? `${stats.autoPublishPct}%` : '—'} label="Auto-published (low risk)" sub="pass auto-scan on first upload" />
        <StatCell value={stats?.activePublishers ?? '—'} label="Active publishers" sub="across the org" />
      </section>

      {/* Popular skills preview */}
      <section className="mx-auto max-w-6xl px-10 py-16">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Live in the registry</p>
        <h2 className="mt-2 text-[28px] font-medium tracking-tight">Skills your team ships with today</h2>
        <p className="mt-2 max-w-xl text-[14px] leading-relaxed text-muted-foreground">
          Every skill is automatically scanned. Low-risk go live instantly. High-risk enter a human review queue.
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
          <span className="text-[11px] font-semibold uppercase tracking-wider">Works with</span>
          <span>Claude Code</span>
          <span>Cursor</span>
          <span>Gemini CLI</span>
          <span>GitHub Copilot</span>
          <span>Cline</span>
          <span className="text-[#A8A49C]">+ more via agentskills.io standard</span>
        </div>
      </section>

      {/* Final CTA */}
      <section className="px-10 py-20 text-center">
        <h2 className="text-[36px] font-medium tracking-tight">Start sharing skills like libraries.</h2>
        <p className="mx-auto mt-3 max-w-md text-[14px] leading-relaxed text-muted-foreground">
          You already share components, configs, and helpers. Skills are the same — packaged for AI agents.
        </p>
        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          <BeamFrame>
            <Link
              to="/browse"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-5 py-2.5 text-[13px] font-medium text-primary-foreground"
            >
              Browse the registry
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </BeamFrame>
          <Link
            to="/publish"
            className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-5 py-2.5 text-[13px] font-medium"
          >
            Publish skill
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-border px-10 py-6">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 text-[12px] text-muted-foreground">
          <span>Skills Hub · enterprise registry for SKILL.md bundles</span>
          <div className="flex gap-5">
            <Link to="/docs/your-first-skill" className="hover:text-foreground">Docs</Link>
            <a href="/v3/api-docs" className="hover:text-foreground">API</a>
            <Link to="/" className="hover:text-foreground">Status</Link>
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
