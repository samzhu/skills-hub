import type { Skill } from '@/types/skill'

interface Props {
  skill: Skill
  /** 14-day download array; [0] = oldest day, [13] = most recent day */
  stats: number[]
}

function weeklyDelta(stats: number[]): number | null {
  if (stats.length < 14) return null
  const lastWeek = stats.slice(0, 7).reduce((a, b) => a + b, 0)
  const thisWeek = stats.slice(7, 14).reduce((a, b) => a + b, 0)
  if (lastWeek === 0) return null
  return Math.round(((thisWeek - lastWeek) / lastWeek) * 100)
}

/**
 * S142a — 4-cell stat strip: Downloads / Rating / Versions / Open flags.
 * Per AC-S142a-10: Downloads delta = frontend-derived from 14d stats array.
 */
export function StatStrip({ skill, stats }: Props) {
  const delta = weeklyDelta(stats)

  return (
    <div
      data-testid="stat-strip"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
      }}
    >
      {/* Downloads */}
      <div style={cellStyle(false)}>
        <div style={labelStyle}>下載次數</div>
        <div style={valStyle}>{skill.downloadCount.toLocaleString()}</div>
        <div style={subStyle}>
          {delta !== null && (
            <span
              data-testid="download-delta"
              style={{ color: delta >= 0 ? 'var(--green-text, #6FD8B0)' : 'var(--red-text, #F08080)' }}
            >
              {delta >= 0 ? '↑' : '↓'} {Math.abs(delta)}% 相較上週
            </span>
          )}
        </div>
      </div>

      {/* Rating */}
      <div style={cellStyle(false)}>
        <div style={labelStyle}>評分</div>
        <div style={valStyle}>
          {skill.reviewCount > 0 ? skill.averageRating.toFixed(1) : '—'}
          {skill.reviewCount > 0 && (
            <span style={{ fontSize: 12, fontWeight: 400, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}> / 5</span>
          )}
        </div>
        <div style={subStyle}>{skill.reviewCount} 則評論</div>
      </div>

      {/* Versions */}
      <div style={cellStyle(false)}>
        <div style={labelStyle}>版本數</div>
        <div data-testid="version-count" style={valStyle}>{skill.versionCount}</div>
        <div style={subStyle}>
          {skill.latestVersionPublishedAt
            ? `最新版本 ${relativeTime(skill.latestVersionPublishedAt)}`
            : '尚無版本'}
        </div>
      </div>

      {/* Open flags */}
      <div style={{ ...cellStyle(true), borderRight: 'none' }}>
        <div style={labelStyle}>待處理旗標</div>
        <div
          data-testid="open-flags"
          style={{
            ...valStyle,
            color: skill.openFlagCount > 0 ? 'var(--red-text, #F08080)' : undefined,
          }}
        >
          {skill.openFlagCount}
        </div>
        <div style={subStyle}>{skill.openFlagCount > 0 ? '活躍回報' : '無活躍回報'}</div>
      </div>
    </div>
  )
}

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const days = Math.floor(diff / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '1 天前'
  return `${days} 天前`
}

const cellStyle = (last: boolean): React.CSSProperties => ({
  padding: '12px 18px',
  borderRight: last ? 'none' : '0.5px solid var(--line, rgba(255,255,255,0.08))',
  borderTop: '0.5px solid var(--line, rgba(255,255,255,0.08))',
})

const labelStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ink-3, rgba(238,236,234,0.4))',
  textTransform: 'uppercase',
  letterSpacing: '.05em',
  marginBottom: 3,
}

const valStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 500,
  letterSpacing: '-.01em',
  marginBottom: 2,
}

const subStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ink-3, rgba(238,236,234,0.4))',
}
