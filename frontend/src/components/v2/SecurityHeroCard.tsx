import type { RiskLevel } from '@/types/skill'

interface Props {
  riskLevel: RiskLevel | null | undefined
  active: boolean
  onClick: () => void
}

interface RiskLightSummary {
  level: RiskLevel
  label: '無風險' | '低風險' | '中風險' | '高風險'
  greenCount: 1 | 2 | 3 | 4
  redCount: 0 | 1 | 2 | 3
  lights: Array<'green' | 'red'>
  subText: string
  color: string
  borderColor: string
}

const RISK_SUMMARY: Record<RiskLevel, Omit<RiskLightSummary, 'level'>> = {
  NONE: {
    label: '無風險',
    greenCount: 4,
    redCount: 0,
    lights: ['green', 'green', 'green', 'green'],
    subText: '4 個綠燈',
    color: 'var(--green-text, #6FD8B0)',
    borderColor: 'rgba(29,158,117,.3)',
  },
  LOW: {
    label: '低風險',
    greenCount: 3,
    redCount: 1,
    lights: ['green', 'green', 'green', 'red'],
    subText: '3 個綠燈 · 1 個紅燈',
    color: 'var(--green-text, #6FD8B0)',
    borderColor: 'rgba(29,158,117,.3)',
  },
  MEDIUM: {
    label: '中風險',
    greenCount: 2,
    redCount: 2,
    lights: ['green', 'green', 'red', 'red'],
    subText: '2 個綠燈 · 2 個紅燈',
    color: 'var(--amber-text, #FAC775)',
    borderColor: 'rgba(239,159,39,.3)',
  },
  HIGH: {
    label: '高風險',
    greenCount: 1,
    redCount: 3,
    lights: ['green', 'red', 'red', 'red'],
    subText: '1 個綠燈 · 3 個紅燈',
    color: 'var(--red-text, #F08080)',
    borderColor: 'rgba(226,75,74,.3)',
  },
}

function riskLightSummary(riskLevel: RiskLevel | null | undefined): RiskLightSummary | null {
  if (!riskLevel) return null
  return { level: riskLevel, ...RISK_SUMMARY[riskLevel] }
}

function cardBorder(summary: RiskLightSummary | null, active: boolean): string {
  if (active) return '0.5px solid rgba(127,119,221,.45)'
  if (summary) return `0.5px solid ${summary.borderColor}`
  return '0.5px solid var(--line, rgba(255,255,255,0.08))'
}

/**
 * S183 — Skill detail header security card.
 * Shows the platform riskLevel as four total-risk lights; lights no longer map to legacy checks.
 */
export function SecurityHeroCard({ riskLevel, active, onClick }: Props) {
  const summary = riskLightSummary(riskLevel)

  return (
    <div
      data-testid="security-hero-card"
      role="button"
      tabIndex={0}
      aria-label="安全性掃描結果"
      onClick={onClick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } }}
      style={{
        background: 'var(--bg-2, rgba(255,255,255,0.04))',
        border: cardBorder(summary, active),
        borderRadius: 16,
        padding: '14px 16px',
        cursor: 'pointer',
        flex: 1,
        minWidth: 0,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 4 }}>
        <span style={{ fontSize: 10, letterSpacing: '.07em', textTransform: 'uppercase', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
          安全性
        </span>
        <span
          data-testid="security-value"
          style={{ fontSize: 22, fontWeight: 500, color: summary?.color }}
        >
          {summary?.label ?? '—'}
        </span>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: summary ? 10 : 0 }}>
        {summary?.subText ?? '未評估'}
      </div>
      {summary && (
        <div data-testid="security-light-row" style={{ display: 'flex', gap: 4, height: 4 }}>
          {summary.lights.map((state, index) => (
            <div
              key={`${state}-${index}`}
              data-testid={`risk-light-${index}`}
              data-state={state}
              aria-label={state === 'green' ? '綠燈' : '紅燈'}
              style={{
                flex: 1,
                minWidth: 0,
                borderRadius: 3,
                background: state === 'green'
                  ? 'var(--green, #1D9E75)'
                  : 'var(--red, #E24B4A)',
              }}
            />
          ))}
        </div>
      )}
    </div>
  )
}
