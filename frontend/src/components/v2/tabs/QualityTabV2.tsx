import { useState } from 'react'
import type { SkillScores, AxisScore, DimensionScore } from '@/api/scores'
import { ScoreDot } from '../shared/ScoreDot'
import { isDimensionScore } from '../shared/scoreStatus'

const AXIS_LABELS: Record<string, { zh: string; sub: string }> = {
  validation:     { zh: '規格驗證',  sub: 'Specification compliance & completeness' },
  implementation: { zh: '實作品質',  sub: 'Code quality & best practices' },
  activation:     { zh: '觸發能力',  sub: 'Discoverability & usage clarity' },
}

function formatDimName(key: string): string {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()).trim()
}

function AxisSection({ axisKey, axis }: { axisKey: string; axis: AxisScore }) {
  const [expanded, setExpanded] = useState(true)
  const label = AXIS_LABELS[axisKey] ?? { zh: axisKey, sub: '' }
  const dimensionEntries = Object.entries(axis.dimensions).filter(
    (entry): entry is [string, DimensionScore] => isDimensionScore(entry[1]),
  )

  return (
    <div data-testid={`axis-${axisKey}`} style={{ marginBottom: 24 }}>
      {/* Section head */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 8 }}>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600 }}>{label.zh}</div>
          <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{label.sub}</div>
        </div>
        <span style={{ fontSize: 22, fontWeight: 500 }}>{axis.totalScore}</span>
      </div>
      {/* Progress bar */}
      <div style={{ height: 3, background: 'rgba(127,119,221,0.12)', borderRadius: 2, marginBottom: 12, overflow: 'hidden' }}>
        <div style={{
          height: '100%',
          width: `${axis.totalScore}%`,
          background: 'linear-gradient(90deg, #7F77DD, #D9388A 60%, #EF9F27)',
        }} />
      </div>
      {/* Dimensions table */}
      <div style={{ background: 'rgba(255,255,255,0.03)', borderRadius: 8, border: '0.5px solid var(--line, rgba(255,255,255,0.08))', overflow: 'hidden' }}>
        {dimensionEntries.map(([key, dim], idx) => (
          <div key={key} style={{ padding: '10px 14px', borderBottom: idx < dimensionEntries.length - 1 ? '0.5px solid var(--line, rgba(255,255,255,0.08))' : 'none' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
              <span style={{ fontSize: 12, color: 'var(--ink-2, rgba(238,236,234,0.7))' }}>{formatDimName(key)}</span>
              <ScoreDot score={dim.score} max={3} />
            </div>
            {dim.reasoning && (
              <div>
                <p data-testid={`reasoning-${key}`} style={{
                  fontSize: 12,
                  color: 'var(--ink-3, rgba(238,236,234,0.4))',
                  lineHeight: 1.55,
                  marginTop: 4,
                  display: expanded ? 'block' : '-webkit-box',
                  WebkitLineClamp: expanded ? 'none' : 3,
                  WebkitBoxOrient: 'vertical',
                  overflow: expanded ? 'visible' : 'hidden',
                }}>
                  {dim.reasoning}
                </p>
                <button
                  data-testid={`toggle-${key}`}
                  onClick={() => setExpanded(e => !e)}
                  style={{ fontSize: 11, color: '#7F77DD', background: 'none', border: 'none', padding: 0, cursor: 'pointer', marginTop: 2 }}
                >
                  {expanded ? '顯示較少' : '顯示更多'}
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

interface Props {
  /** undefined = loading；null = 尚未評分 */
  scores: SkillScores | null | undefined
}

/**
 * S142a — Quality tab v2 with 3 sections + ScoreDot + reasoning expand/collapse.
 * Reuses AXIS_LABELS from S135b (規格驗證 / 實作品質 / 觸發能力).
 */
export function QualityTabV2({ scores }: Props) {
  if (scores === undefined) {
    return <div data-testid="quality-tab-loading" style={{ padding: 24 }}>
      <div className="animate-pulse" style={{ height: 200, background: 'rgba(255,255,255,0.05)', borderRadius: 8 }} />
    </div>
  }

  if (scores === null) {
    return (
      <div data-testid="quality-tab-empty" style={{ padding: 24, textAlign: 'center', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
        <div style={{ fontSize: 24, marginBottom: 8 }}>—</div>
        {/* S151: 對齊 hero card / SkillScoreBadge 的「評分計算中」風格；tab empty state 用完整版含「請稍後重新整理」hint */}
        <div>評分計算中，請稍後重新整理</div>
      </div>
    )
  }

  const axes = [
    { key: 'validation',     axis: scores.validation },
    { key: 'implementation', axis: scores.implementation },
    { key: 'activation',     axis: scores.activation },
  ]

  return (
    <div data-testid="quality-tab-v2" style={{ padding: '16px 0' }}>
      {axes.map(({ key, axis }) => (
        <AxisSection key={key} axisKey={key} axis={axis} />
      ))}
    </div>
  )
}
