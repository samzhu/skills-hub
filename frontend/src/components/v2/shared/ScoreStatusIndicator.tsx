import { scoreStatus, TONE_COLORS } from './scoreStatus'
import type { QualityAxisKey, StatusTone } from './scoreStatus'

interface StatusIndicatorProps {
  tone: StatusTone
  label: string
}

function StatusIndicator({ tone, label }: StatusIndicatorProps) {
  return (
    <span
      data-testid="score-status-indicator"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        color: 'var(--ink-2, rgba(238,236,234,0.7))',
        fontSize: 12,
        lineHeight: 1.2,
        whiteSpace: 'nowrap',
      }}
    >
      <span
        data-testid="score-status-dot"
        style={{
          display: 'inline-block',
          width: 12,
          height: 12,
          borderRadius: '50%',
          background: TONE_COLORS[tone],
          flexShrink: 0,
        }}
      />
      <span>{label}</span>
    </span>
  )
}

/** S201 — renders a 12px dot plus text so quality status does not rely on color alone. */
export function ScoreStatusIndicator({ axisKey, score }: { axisKey: QualityAxisKey; score: number }) {
  return <StatusIndicator {...scoreStatus(axisKey, score)} />
}

/** S201 — renders validation warning count as a status row instead of a 0-3 score. */
export function WarningStatusIndicator({ count }: { count: number }) {
  return <StatusIndicator tone="warn" label={`提醒 ${count}`} />
}
