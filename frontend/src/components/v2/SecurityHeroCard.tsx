import type { SecurityReport } from '@/api/security'

interface Props {
  report: SecurityReport | null | undefined
  active: boolean
  onClick: () => void
}

const QUAD_ORDER: Array<keyof SecurityReport['checks']> = ['shell', 'paths', 'secrets', 'deps']
const QUAD_LABELS = ['Shell', 'Paths', 'Secrets', 'Deps']

const SEG_COLOR: Record<string, string> = {
  PASS: '#1D9E75',
  WARN: '#EF9F27',
  FAIL: '#E24B4A',
}

function overallLabel(report: SecurityReport) {
  if (report.overall === 'PASS') return 'Passed'
  const issues = QUAD_ORDER.filter(q => report.checks[q].status !== 'PASS').length
  return `${issues} Issue${issues > 1 ? 's' : ''}`
}

function overallColor(overall: string) {
  if (overall === 'PASS') return 'var(--green-text, #6FD8B0)'
  if (overall === 'WARN') return 'var(--amber-text, #FAC775)'
  return 'var(--red-text, #F08080)'
}

function cardBorder(report: SecurityReport | null | undefined, active: boolean): string {
  if (active) return '0.5px solid rgba(127,119,221,.45)'
  if (report?.overall === 'WARN') return '0.5px solid rgba(239,159,39,.3)'
  if (report?.overall === 'FAIL') return '0.5px solid rgba(226,75,74,.3)'
  return '0.5px solid var(--line, rgba(255,255,255,0.08))'
}

function subText(report: SecurityReport): string {
  if (report.overall === 'PASS') return 'No known issues'
  const firstIssue = QUAD_ORDER.find(q => report.checks[q].status !== 'PASS')
  return firstIssue ? report.checks[firstIssue].detail : ''
}

/**
 * S142a — Security hero card (HeroMetricsRow right column).
 * Displays 4-segment bar + overall status. Click → Security tab.
 */
export function SecurityHeroCard({ report, active, onClick }: Props) {
  return (
    <div
      data-testid="security-hero-card"
      onClick={onClick}
      style={{
        background: 'var(--bg-2, rgba(255,255,255,0.04))',
        border: cardBorder(report, active),
        borderRadius: 16,
        padding: '14px 16px',
        cursor: 'pointer',
        flex: 1,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 10, letterSpacing: '.07em', textTransform: 'uppercase', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
          SECURITY
        </span>
        <span
          data-testid="security-value"
          style={{ fontSize: 22, fontWeight: 500, color: report ? overallColor(report.overall) : undefined }}
        >
          {report ? overallLabel(report) : '—'}
        </span>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        {report ? subText(report) : '未掃描'}
      </div>
      {/* 4-segment bar */}
      <div data-testid="security-seg-row" style={{ display: 'flex', gap: 3, height: 3, marginBottom: 8 }}>
        {QUAD_ORDER.map((q, _i) => (
          <div
            key={q}
            data-testid={`seg-${q}`}
            style={{
              flex: 1,
              borderRadius: 2,
              background: report
                ? SEG_COLOR[report.checks[q].status] ?? 'rgba(238,236,234,0.15)'
                : 'rgba(238,236,234,0.1)',
            }}
          />
        ))}
      </div>
      {/* labels */}
      <div style={{ display: 'flex', gap: 10, fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
        {QUAD_LABELS.map((l, i) => (
          <span key={l}>
            {l}{i < QUAD_LABELS.length - 1 && <span style={{ margin: '0 4px' }}>·</span>}
          </span>
        ))}
      </div>
    </div>
  )
}
