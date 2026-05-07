import type { SecurityReport } from '@/api/security'

interface Props {
  report: SecurityReport
}

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '1 天前'
  return `${days} 天前`
}

export function SecurityAuditCard({ report }: Props) {
  const rows = [
    { label: 'Last scanned', value: relativeTime(report.scannedAt), mono: false },
    { label: 'Engine', value: `risk-scanner ${report.engineVersion}`, mono: true },
    { label: 'Rule set', value: report.ruleSetVersion, mono: true },
    { label: 'Dep scan', value: 'not yet enabled', mono: false },
  ]

  return (
    <div data-testid="security-audit-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        安全稽核
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {rows.map(r => (
          <div key={r.label} style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{r.label}</span>
            <span style={{ fontSize: 11, fontFamily: r.mono ? 'monospace' : undefined }}>{r.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
