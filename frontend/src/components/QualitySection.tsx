import type { SkillScores } from '@/api/scores'

/** S135b §4.5 — score tier 對應顏色。 */
function scoreTierColor(total: number): string {
  if (total >= 80) return '#9FE1CB'  // 綠
  if (total >= 60) return '#FAC775'  // 黃
  return '#F2A6A6'                    // 紅
}

const RISK_LABEL: Record<string, string> = {
  NONE:   '無風險',
  LOW:    '低風險',
  MEDIUM: '中等風險',
  HIGH:   '高風險',
}

const RISK_COLOR: Record<string, string> = {
  NONE:   '#9FE1CB',
  LOW:    '#9FE1CB',
  MEDIUM: '#FAC775',
  HIGH:   '#F2A6A6',
}

interface QualitySectionProps {
  /** undefined = loading；null = 尚未評分；object = 評分完成 */
  scores: SkillScores | null | undefined
  /** 來自 skill.riskLevel：'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | null */
  riskLevel: string | null | undefined
}

/**
 * S135b — SkillDetailPage hero 下方品質信號區塊。
 * 顯示「品質分數」進度條 + 「安全等級」文字。
 */
export function QualitySection({ scores, riskLevel }: QualitySectionProps) {
  return (
    <div className="mb-6 space-y-3 rounded-md border border-border bg-card p-4">
      {/* 品質分數行 */}
      <div>
        <div className="mb-1.5 flex items-center justify-between text-[13px]">
          <span className="font-medium">品質分數</span>
          {scores === undefined && (
            <span className="text-muted-foreground">載入中...</span>
          )}
          {scores === null && (
            <span className="text-muted-foreground text-[12px]">評分計算中，請稍後重新整理</span>
          )}
          {scores && (
            <span className="font-semibold" style={{ color: scoreTierColor(scores.total) }}>
              {scores.total}%
            </span>
          )}
        </div>
        {/* progress bar */}
        <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
          {scores === undefined && (
            <div className="h-full w-1/3 animate-pulse rounded-full bg-muted-foreground/30" />
          )}
          {scores === null && (
            <div className="h-full w-0 rounded-full bg-muted-foreground/20" />
          )}
          {scores && (
            <div
              className="h-full rounded-full transition-all duration-500"
              style={{
                width: `${scores.total}%`,
                backgroundColor: scoreTierColor(scores.total),
              }}
            />
          )}
        </div>
      </div>

      {/* 安全等級行（從 skill.riskLevel，不重複 API call） */}
      <div className="flex items-center justify-between text-[13px]">
        <span className="font-medium">安全等級</span>
        {riskLevel ? (
          <span
            className="rounded-full px-2 py-0.5 text-[11px] font-medium"
            style={{
              backgroundColor: `${RISK_COLOR[riskLevel] ?? '#9FE1CB'}22`,
              color: RISK_COLOR[riskLevel] ?? '#9FE1CB',
            }}
          >
            {RISK_LABEL[riskLevel] ?? riskLevel}
          </span>
        ) : (
          <span className="text-[12px] text-muted-foreground">評估中</span>
        )}
      </div>
    </div>
  )
}
