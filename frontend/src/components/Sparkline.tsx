/**
 * S096d3 — SVG polyline sparkline 給 MySkills SkillRow / SkillDetail trend 用。
 *
 * 不引 chart library — 30 個 data point 用 native SVG `<polyline>` 即可，bundle 0 dep。
 * Auto-scales：max value normalized to chart height；全 0 顯水平基準線。
 *
 * @param data 整數陣列（每天下載量；index 0 = 最舊，index N-1 = 今天）
 * @param width 視覺寬度（px；預設 60）
 * @param height 視覺高度（px；預設 18）
 * @param color stroke 顏色；預設用 DESIGN.md `--color-accent`（purple #7F77DD）
 */
export function Sparkline({
  data,
  width = 60,
  height = 18,
  color = '#7F77DD',
}: {
  data: number[]
  width?: number
  height?: number
  color?: string
}) {
  if (!data || data.length === 0) {
    return <span className="inline-block text-[10px] text-muted-foreground">—</span>
  }
  const max = Math.max(...data, 1) // 防 0 division；max=0 → flat line at bottom
  const stepX = data.length > 1 ? width / (data.length - 1) : 0
  const points = data
    .map((v, i) => {
      const x = i * stepX
      const y = height - (v / max) * height
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="inline-block align-middle">
      <polyline
        points={points}
        fill="none"
        stroke={color}
        strokeWidth={1.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
