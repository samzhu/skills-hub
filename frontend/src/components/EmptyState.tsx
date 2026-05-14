import { ArrowRight, Check, FileText, Upload } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { BeamFrame } from './BeamFrame'

/**
 * S094c → S098h2 — 4-tone empty state component (dark theme migration).
 *
 * 對齊 docs/grimo/ui/prototype/empty_state_collection_four_tones.html (v2 dark)。
 * S098h2 sister fix to S098h: 原 light-theme inline hex (`#181818` text on
 * `bg-white` container) 在 v2 dark page 上 theme-mismatch — 視覺與其他元件
 * （SkillCard / FieldCard 等已 dark-token 的元件）對不上。
 *
 * 每 tone 的 voice：
 * - seed       Fresh deployment / Seeding — 平台空殼，激勵第一次貢獻
 * - invite     New author / Invitational — 個人空殼，邀請加入
 * - redirect   No search results / Redirecting — 搜尋失敗，導流到其他路徑
 * - clear      Review queue all-clear / Celebratory — 沒事可做，安撫
 *
 * tone 不會自動推導 — 由 caller 決定（caller 知道 context：總數 vs query 0 vs admin idle）。
 * 不為了減少 prop 而把不同 tone 合在一起 — 每 tone 有 distinct 結構，硬合會讓元件變成 god-component。
 */

export type EmptyStateTone = 'seed' | 'invite' | 'redirect' | 'clear'

export interface EmptyStateProps {
  tone: EmptyStateTone
  headline: string
  sub?: string
  /** seed: eyebrow with pulse dot ("0 skills · 0 publishers") */
  eyebrow?: string
  /** redirect: echo of user query */
  query?: string
  /** redirect: list of suggestion items; actionable items render as links/buttons. */
  suggestions?: Array<{ text: string; hint?: string; href?: string; onClick?: () => void }>
  /** clear: list of stat items {value, label} (max 3) */
  stats?: Array<{ value: string; label: string; delta?: string }>
  /** invite: optional 4-step horizontal flow (e.g. ['打包', '自動掃描', '發佈', '追蹤']);
   *  undefined / [] → strip 不顯。Caller opt-in：dev / publish onboarding context 顯，
   *  其他 reuse context (community stub / search empty / reviews stub) 不顯，避免 context 不對齊。 */
  steps?: string[]
  /** Primary CTA — wrapped in BeamFrame for visual emphasis (per DESIGN.md primary action) */
  primaryAction?: { label: string; onClick?: () => void; href?: string }
  /** Secondary CTA — outline / link style */
  secondaryAction?: { label: string; onClick?: () => void; href?: string }
  /** clear: optional bottom audit-log link */
  auditLink?: { label: string; href: string }
}

export function EmptyState(props: EmptyStateProps) {
  switch (props.tone) {
    case 'seed':
      return <SeedTone {...props} />
    case 'invite':
      return <InviteTone {...props} />
    case 'redirect':
      return <RedirectTone {...props} />
    case 'clear':
      return <ClearTone {...props} />
  }
}

function PrimaryButton({ action }: { action: NonNullable<EmptyStateProps['primaryAction']> }) {
  // Dark theme primary CTA 反白：bg ink + text bg
  const inner = (
    <span className="inline-flex items-center gap-1.5 rounded-md bg-[#EEECEA] px-4 py-2 text-[13px] font-medium text-[#08080A]">
      {action.label}
      <ArrowRight className="h-3 w-3" />
    </span>
  )
  return (
    <BeamFrame>
      {action.href ? (
        <Link to={action.href}>{inner}</Link>
      ) : (
        <button type="button" onClick={action.onClick}>{inner}</button>
      )}
    </BeamFrame>
  )
}

function SecondaryButton({ action }: { action: NonNullable<EmptyStateProps['secondaryAction']> }) {
  const cls = "inline-flex items-center gap-1.5 rounded-md border border-[rgba(255,255,255,0.10)] bg-[#171719] px-4 py-2 text-[13px] font-medium text-[#EEECEA] hover:bg-[#1F1F22]"
  return action.href ? (
    <Link to={action.href} className={cls}>{action.label}</Link>
  ) : (
    <button type="button" onClick={action.onClick} className={cls}>{action.label}</button>
  )
}

function Container({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-lg border border-[rgba(255,255,255,0.06)] bg-[#0F0F12] px-8 py-10 ${className}`}>
      {children}
    </div>
  )
}

// ============ Seed tone ============

function SeedTone(props: EmptyStateProps) {
  return (
    <Container>
      <div className="grid gap-8 md:grid-cols-[1fr_auto] md:items-start">
        <div>
          {props.eyebrow && (
            <span className="mb-3 inline-flex items-center gap-1.5 rounded-full border border-[rgba(127,119,221,0.30)] bg-[rgba(127,119,221,0.10)] px-2.5 py-0.5 text-[11px] font-medium text-[#C9C5F2]">
              <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-[#7F77DD]" />
              {props.eyebrow}
            </span>
          )}
          <h2 className="text-[22px] font-semibold tracking-tight text-[#EEECEA]">{props.headline}</h2>
          {props.sub && <p className="mt-2 max-w-md text-[13px] leading-relaxed text-[#A8A49C]">{props.sub}</p>}
          {(props.primaryAction || props.secondaryAction) && (
            <div className="mt-5 flex flex-wrap items-center gap-3">
              {props.primaryAction && <PrimaryButton action={props.primaryAction} />}
              {props.secondaryAction && <SecondaryButton action={props.secondaryAction} />}
            </div>
          )}
        </div>
        {/* Ghost preview cards — show the user what populated state will look like */}
        <div className="hidden grid-cols-2 gap-2 md:grid" aria-hidden="true">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-20 w-32 rounded-md border border-dashed border-[rgba(255,255,255,0.10)] bg-[#171719]" />
          ))}
        </div>
      </div>
    </Container>
  )
}

// ============ Invite tone ============

function InviteTone(props: EmptyStateProps) {
  // S105: steps 從 hardcoded 改 caller opt-in；undefined / [] → strip 不顯
  // 避免 component 在多 context reuse 時偷渡無關 publish onboarding flow（reviews/collections/requests/search empty）
  const steps = props.steps
  return (
    <Container className="text-center">
      <div className="mx-auto mb-4 flex h-11 w-11 items-center justify-center rounded-full border border-[rgba(255,255,255,0.06)] bg-[#171719] text-[#A8A49C]">
        <Upload className="h-4 w-4" />
      </div>
      <h2 className="text-[20px] font-semibold tracking-tight text-[#EEECEA]">{props.headline}</h2>
      {props.sub && <p className="mx-auto mt-2 max-w-md text-[13px] leading-relaxed text-[#A8A49C]">{props.sub}</p>}
      {steps && steps.length > 0 && (
        <div className="mx-auto mt-6 flex max-w-md items-center justify-center gap-2 text-[11px] text-[#A8A49C]">
          {steps.map((label, i) => (
            <div key={label} className="flex items-center gap-2">
              <div className="flex flex-col items-center">
                <div className="flex h-7 w-7 items-center justify-center rounded-full border border-dashed border-[rgba(255,255,255,0.10)] text-[#A8A49C]">
                  <span className="text-[10px] font-medium">{i + 1}</span>
                </div>
                <span className="mt-1.5 text-[10px] font-medium uppercase tracking-wider">{label}</span>
              </div>
              {i < steps.length - 1 && <div className="h-px w-6 bg-[rgba(255,255,255,0.10)]" />}
            </div>
          ))}
        </div>
      )}
      {(props.primaryAction || props.secondaryAction) && (
        <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
          {props.primaryAction && <PrimaryButton action={props.primaryAction} />}
          {props.secondaryAction && <SecondaryButton action={props.secondaryAction} />}
        </div>
      )}
    </Container>
  )
}

// ============ Redirect tone ============

function RedirectTone(props: EmptyStateProps) {
  return (
    <Container>
      <div className="grid gap-8 md:grid-cols-[1fr_1fr]">
        <div>
          {props.query && (
            <p className="mb-3 text-[12px] text-[#A8A49C]">
              查詢 · <span className="font-mono text-[#EEECEA]">"{props.query}"</span>
            </p>
          )}
          <h2 className="text-[20px] font-semibold tracking-tight text-[#EEECEA]">{props.headline}</h2>
          {props.sub && <p className="mt-2 text-[13px] leading-relaxed text-[#A8A49C]">{props.sub}</p>}
          {/* S104: primaryAction render for redirect tone（既有 EmptyStateProps interface 已支援，
              但 RedirectTone 過去未 render；filter-active-empty 場景需要明確 escape hatch button） */}
          {(props.primaryAction || props.secondaryAction) && (
            <div className="mt-5 flex flex-wrap items-center gap-3">
              {props.primaryAction && <PrimaryButton action={props.primaryAction} />}
              {props.secondaryAction && <SecondaryButton action={props.secondaryAction} />}
            </div>
          )}
        </div>
        {props.suggestions && props.suggestions.length > 0 && (
          <div className="flex flex-col gap-2">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-[#A8A49C]">你可以這樣做</p>
            {props.suggestions.map((s, i) => (
              <SuggestionRow key={i} suggestion={s} />
            ))}
          </div>
        )}
      </div>
    </Container>
  )
}

function SuggestionRow({ suggestion }: { suggestion: NonNullable<EmptyStateProps['suggestions']>[number] }) {
  const content = (
    <>
      <div className="flex flex-col">
        <span className="text-[13px] font-medium text-[#EEECEA]">{suggestion.text}</span>
        {suggestion.hint && <span className="mt-0.5 text-[11px] text-[#A8A49C]">{suggestion.hint}</span>}
      </div>
      {(suggestion.href || suggestion.onClick) && <ArrowRight className="h-3.5 w-3.5 shrink-0 text-[#A8A49C]" />}
    </>
  )
  const className = "flex w-full items-center justify-between gap-3 rounded-md border border-[rgba(255,255,255,0.06)] bg-[#171719] px-3 py-2.5 text-left"

  if (suggestion.href) {
    return (
      <Link to={suggestion.href} className={`${className} hover:bg-[#1F1F22] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring`}>
        {content}
      </Link>
    )
  }

  if (suggestion.onClick) {
    return (
      <button
        type="button"
        onClick={suggestion.onClick}
        className={`${className} hover:bg-[#1F1F22] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring`}
      >
        {content}
      </button>
    )
  }

  return (
    <div className={`${className} border-dashed opacity-80`}>
      {content}
    </div>
  )
}

// ============ Clear tone ============

function ClearTone(props: EmptyStateProps) {
  return (
    <Container className="text-center">
      <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-[rgba(29,158,117,0.14)]">
        <Check className="h-6 w-6 text-[#6FD8B0]" strokeWidth={2.5} />
      </div>
      <h2 className="text-[20px] font-semibold tracking-tight text-[#EEECEA]">{props.headline}</h2>
      {props.sub && <p className="mx-auto mt-2 max-w-md text-[13px] leading-relaxed text-[#A8A49C]">{props.sub}</p>}
      {props.stats && props.stats.length > 0 && (
        <div className="mx-auto mt-6 flex max-w-md items-center justify-center divide-x divide-[rgba(255,255,255,0.06)]">
          {props.stats.map((s, i) => (
            <div key={i} className="px-5">
              <p className="font-mono text-[15px] font-semibold tabular-nums text-[#EEECEA]">
                {s.value}
                {s.delta && <span className="ml-1 text-[11px] font-normal text-[#6FD8B0]">{s.delta}</span>}
              </p>
              <p className="mt-1 text-[10px] font-semibold uppercase tracking-wider text-[#A8A49C]">{s.label}</p>
            </div>
          ))}
        </div>
      )}
      {props.auditLink && (
        <Link to={props.auditLink.href} className="mt-5 inline-flex items-center gap-1.5 text-[12px] text-[#A8A49C] hover:text-[#EEECEA]">
          <FileText className="h-3 w-3" />
          {props.auditLink.label}
          <ArrowRight className="h-3 w-3" />
        </Link>
      )}
    </Container>
  )
}
