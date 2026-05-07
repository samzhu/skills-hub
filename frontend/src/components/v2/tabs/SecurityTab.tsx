import type { SecurityReport, SecurityCheck } from '@/api/security'

// Per spec §4.4: PASS segments use 4-step gradient
const SEG_PASS_COLORS = ['#1D9E75', '#2DB88B', '#4AC9A0', '#6FD8B0']
const QUAD_ORDER: Array<keyof SecurityReport['checks']> = ['shell', 'paths', 'secrets', 'deps']
const QUAD_LABELS = ['Shell', 'Paths', 'Secrets', 'Deps']

function segmentColor(check: SecurityCheck, idx: number): string {
  if (check.status === 'FAIL') return 'var(--red, #E24B4A)'
  if (check.status === 'WARN') return 'var(--amber, #EF9F27)'
  return SEG_PASS_COLORS[idx] ?? '#1D9E75'
}

function overallCount(report: SecurityReport): number {
  return QUAD_ORDER.filter(q => report.checks[q].status !== 'PASS').length
}

// Shield SVG for different states
function ShieldIcon({ overall }: { overall: string }) {
  const isPass = overall === 'PASS'
  const isWarn = overall === 'WARN'
  const stroke = isPass ? '#1D9E75' : isWarn ? '#EF9F27' : '#E24B4A'
  const fill = isPass ? 'rgba(29,158,117,0.15)' : isWarn ? 'rgba(239,159,39,0.12)' : 'rgba(226,75,74,0.12)'
  const iconStroke = isPass ? '#6FD8B0' : isWarn ? '#FAC775' : '#F08080'

  return (
    <svg width="56" height="64" viewBox="0 0 56 64" fill="none" aria-label={`Security ${overall}`}>
      <path d="M28 2 L52 12 L52 32 C52 48 28 62 28 62 C28 62 4 48 4 32 L4 12 Z"
        fill={fill} stroke={stroke} strokeWidth="1.5" />
      {isPass && (
        <path d="M18 32 L24 38 L38 24" stroke={iconStroke} strokeWidth="2.5"
          strokeLinecap="round" strokeLinejoin="round" />
      )}
      {isWarn && (
        <g>
          <line x1="28" y1="22" x2="28" y2="38" stroke={iconStroke} strokeWidth="2.5" strokeLinecap="round" />
          <circle cx="28" cy="44" r="1.5" fill={iconStroke} />
        </g>
      )}
      {overall === 'FAIL' && (
        <g>
          <line x1="20" y1="24" x2="36" y2="40" stroke={iconStroke} strokeWidth="2.5" strokeLinecap="round" />
          <line x1="36" y1="24" x2="20" y2="40" stroke={iconStroke} strokeWidth="2.5" strokeLinecap="round" />
        </g>
      )}
    </svg>
  )
}

function heroTitle(report: SecurityReport): string {
  if (report.overall === 'PASS') return 'No security issues found'
  const n = overallCount(report)
  if (report.overall === 'WARN') return `${n} issue${n > 1 ? 's' : ''} require${n === 1 ? 's' : ''} review`
  return `${n} issue${n > 1 ? 's' : ''} require${n > 1 ? '' : 's'} attention`
}

function heroColor(overall: string): string {
  if (overall === 'PASS') return 'var(--green-text, #6FD8B0)'
  if (overall === 'WARN') return 'var(--amber-text, #FAC775)'
  return 'var(--red-text, #F08080)'
}

function statusBadgeText(status: string): string {
  if (status === 'PASS') return '✓ Passed'
  if (status === 'WARN') return '! Review'
  return '✗ Fail'
}

function statusBadgeColor(status: string): string {
  if (status === 'PASS') return '#6FD8B0'
  if (status === 'WARN') return '#FAC775'
  return '#F08080'
}

function QuadCard({ check, label, idx }: { check: SecurityCheck; label: string; idx: number }) {
  const color = segmentColor(check, idx)
  return (
    <div
      data-testid={`quad-${label.toLowerCase()}`}
      style={{
        padding: '14px 16px',
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderLeft: `3px solid ${color}`,
        borderRadius: 8,
        background: 'rgba(255,255,255,0.02)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <span style={{ fontSize: 13, fontWeight: 500 }}>{label}</span>
        <span style={{ fontSize: 11, color: statusBadgeColor(check.status) }}>
          {statusBadgeText(check.status)}
        </span>
      </div>
      <p style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))', margin: 0 }}>
        {check.detail}
      </p>
    </div>
  )
}

interface Props {
  /** undefined = loading；null = 404 SECURITY_NOT_SCANNED */
  report: SecurityReport | null | undefined
}

/**
 * S142a — Security tab with shield hero + 4-quad cards.
 * Shield colors: PASS=green / WARN=amber / FAIL=red.
 */
export function SecurityTab({ report }: Props) {
  if (report === undefined) {
    return <div data-testid="security-tab-loading" style={{ padding: 24 }}>
      <div className="animate-pulse" style={{ height: 200, background: 'rgba(255,255,255,0.05)', borderRadius: 8 }} />
    </div>
  }

  if (report === null) {
    return (
      <div data-testid="security-tab-empty" style={{ padding: 24, textAlign: 'center', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
        <div style={{ fontSize: 24, marginBottom: 8 }}>🔍</div>
        <div>Security report 尚未掃描</div>
      </div>
    )
  }

  const scannedDate = new Date(report.scannedAt).toLocaleDateString('zh-TW')
  const metaSub = `Scanned ${scannedDate} · engine ${report.engineVersion} · rule set ${report.ruleSetVersion}`

  return (
    <div data-testid="security-tab" style={{ padding: '16px 0' }}>
      {/* Hero */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '24px 0 28px' }}>
        <ShieldIcon overall={report.overall} />
        <div data-testid="security-hero-title" style={{ fontSize: 18, fontWeight: 600, marginTop: 12, color: heroColor(report.overall) }}>
          {heroTitle(report)}
        </div>
        <div style={{ fontSize: 12, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginTop: 4 }}>
          {metaSub}
        </div>
      </div>

      {/* 4-quad cards */}
      <div data-testid="security-quads" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
        {QUAD_ORDER.map((q, i) => (
          <QuadCard key={q} check={report.checks[q]} label={QUAD_LABELS[i]} idx={i} />
        ))}
      </div>
    </div>
  )
}
