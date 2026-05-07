/**
 * S142a — Score tier color dot (per 工程說明 §7):
 * full (score 3/3)  → accent (#7F77DD)
 * good (>= 60%)     → amber
 * low  (< 60%)      → red
 */
export function ScoreDot({ score, max = 3 }: { score: number; max?: number }) {
  const pct = max > 0 ? score / max : 0
  const color = pct === 1 ? '#7F77DD' : pct >= 0.6 ? '#EF9F27' : '#E24B4A'
  return (
    <span
      data-testid="score-dot"
      style={{
        display: 'inline-block',
        width: 7,
        height: 7,
        borderRadius: '50%',
        background: color,
        flexShrink: 0,
      }}
    />
  )
}
