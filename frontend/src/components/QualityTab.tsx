import type { SkillScores, AxisScore, DimensionScore } from '@/api/scores'
import { isDimensionScore } from './v2/shared/scoreStatus'

/** S135b §4.4 — axis 名稱中文對照（不可更動）。 */
const AXIS_LABELS: Record<string, string> = {
  validation:     '規格驗證',
  implementation: '實作品質',
  activation:     '觸發能力',
}

/** dimension key → 可讀標籤（camelCase 拆開）。 */
function formatDimName(key: string): string {
  return key
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, (s) => s.toUpperCase())
    .trim()
}

/** 0-3 分顯示成填滿圓點。 */
function ScoreDots({ score, max = 3 }: { score: number; max?: number }) {
  return (
    <span className="flex items-center gap-0.5" aria-label={`${score}/${max}`}>
      {Array.from({ length: max }, (_, i) => (
        <span
          key={i}
          className="inline-block h-2 w-2 rounded-full"
          style={i < score ? { backgroundColor: '#9FE1CB' } : { backgroundColor: 'rgba(255,255,255,0.12)' }}
        />
      ))}
    </span>
  )
}

function AxisSection({ label, axis }: { label: string; axis: AxisScore }) {
  const dimensionEntries = Object.entries(axis.dimensions).filter(
    (entry): entry is [string, DimensionScore] => isDimensionScore(entry[1]),
  )

  return (
    <div className="mb-6">
      <div className="mb-2 flex items-center justify-between">
        <h4 className="text-[13px] font-semibold">{label}</h4>
        <span className="text-[12px] font-medium text-muted-foreground">{axis.totalScore}%</span>
      </div>
      <div className="space-y-2 rounded-md border border-border bg-muted/30 p-3">
        {dimensionEntries.map(([key, dim]) => (
          <div key={key}>
            <div className="flex items-center justify-between gap-2">
              <span className="text-[12px] text-muted-foreground">{formatDimName(key)}</span>
              <ScoreDots score={dim.score} />
            </div>
            {dim.reasoning && (
              <p className="mt-0.5 text-[11.5px] leading-relaxed text-muted-foreground/70">
                {dim.reasoning}
              </p>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

interface QualityTabProps {
  /** undefined = loading；null = 尚未評分 */
  scores: SkillScores | null | undefined
}

/**
 * S135b — 品質 tab 內容：3-axis dimension 明細 + reasoning。
 */
export function QualityTab({ scores }: QualityTabProps) {
  if (scores === undefined) {
    return (
      <div className="space-y-4">
        {[1, 2, 3].map((i) => (
          <div key={i} className="h-20 animate-pulse rounded-md bg-muted" />
        ))}
      </div>
    )
  }

  if (scores === null) {
    return (
      <p className="text-[13px] text-muted-foreground">此版本尚未評分，請發布後稍候片刻再重新整理。</p>
    )
  }

  return (
    <div>
      <AxisSection label={AXIS_LABELS.validation}     axis={scores.validation} />
      <AxisSection label={AXIS_LABELS.implementation} axis={scores.implementation} />
      <AxisSection label={AXIS_LABELS.activation}     axis={scores.activation} />
      <p className="mt-2 text-[11px] text-muted-foreground/60">
        評分版本：{scores.skillVersion} · 評估時間：{new Date(scores.evaluatedAt).toLocaleString('zh-TW')}
      </p>
    </div>
  )
}
