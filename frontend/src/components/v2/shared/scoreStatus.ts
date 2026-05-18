import type { DimensionScore, DimensionValue } from '@/api/scores'

export type QualityAxisKey = 'validation' | 'implementation' | 'activation'
export type StatusTone = 'pass' | 'warn' | 'fail'

export const TONE_COLORS: Record<StatusTone, string> = {
  pass: '#1D9E75',
  warn: '#EF9F27',
  fail: '#E24B4A',
}

/** S201 — maps score axis values to user-facing quality status labels. */
export function scoreStatus(axisKey: QualityAxisKey, score: number): { tone: StatusTone; label: string } {
  if (axisKey === 'validation') {
    if (score >= 100) return { tone: 'pass', label: `通過 ${score}/100` }
    if (score > 0) return { tone: 'warn', label: `注意 ${score}/100` }
    return { tone: 'fail', label: '需修正 0/100' }
  }

  if (score >= 3) return { tone: 'pass', label: '滿分 3/3' }
  if (score === 2) return { tone: 'warn', label: '可接受 2/3' }
  if (score === 1) return { tone: 'fail', label: '偏弱 1/3' }
  return { tone: 'fail', label: '缺失 0/3' }
}

/** S201 — keeps validation warnings out of numeric score formatting. */
export function isDimensionScore(value: DimensionValue): value is DimensionScore {
  return typeof value === 'object' && value !== null && !Array.isArray(value) && typeof value.score === 'number'
}
