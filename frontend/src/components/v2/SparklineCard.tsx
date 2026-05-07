import { Sparkline } from '@/components/Sparkline'

interface Props {
  stats: number[]
}

function sum(arr: number[], from: number, to: number): number {
  return arr.slice(from, to).reduce((a, b) => a + b, 0)
}

export function SparklineCard({ stats }: Props) {
  const last30 = stats.slice(-30)
  const last7 = sum(stats, Math.max(0, stats.length - 7), stats.length)
  const last30total = sum(stats, Math.max(0, stats.length - 30), stats.length)

  return (
    <div data-testid="sparkline-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 8 }}>
        30 天下載趨勢
      </div>
      <Sparkline data={last30} width={160} height={32} />
      <div style={{ display: 'flex', gap: 18, marginTop: 10 }}>
        <div>
          <div style={{ fontSize: 12, fontWeight: 600 }}>{last7 || '—'}</div>
          <div style={{ fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>7 天</div>
        </div>
        <div>
          <div style={{ fontSize: 12, fontWeight: 600 }}>{last30total || '—'}</div>
          <div style={{ fontSize: 10, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>30 天</div>
        </div>
      </div>
    </div>
  )
}
