import type { SkillScores } from '@/api/scores'

interface Props {
  scores: SkillScores | null | undefined
  active: boolean
  onClick: () => void
}

/**
 * S142a — Quality hero card (HeroMetricsRow left-center column).
 * Displays quality percentage + axis breakdown. Click → Quality tab.
 */
export function QualityHeroCard({ scores, active, onClick }: Props) {
  const pct = scores ? scores.total : null

  return (
    <div
      data-testid="quality-hero-card"
      role="button"
      tabIndex={0}
      aria-label="品質分析"
      onClick={onClick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } }}
      style={{
        background: 'var(--bg-2, rgba(255,255,255,0.04))',
        border: active
          ? '0.5px solid rgba(127,119,221,.45)'
          : '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderRadius: 16,
        padding: '14px 16px',
        cursor: 'pointer',
        flex: 1,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 10, letterSpacing: '.07em', textTransform: 'uppercase', color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
          品質
        </span>
        <span data-testid="quality-pct" style={{ fontSize: 22, fontWeight: 500 }}>
          {pct !== null ? `${pct}%` : '—'}
        </span>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        {pct === null ? '評分計算中' : '是否符合最佳實踐？'}
      </div>
      {/* progress bar */}
      <div style={{ height: 3, background: 'rgba(127,119,221,0.12)', borderRadius: 2, marginBottom: 8, overflow: 'hidden' }}>
        {pct !== null && (
          <div
            data-testid="quality-bar"
            style={{
              height: '100%',
              width: `${pct}%`,
              background: 'linear-gradient(90deg, #7F77DD, #D9388A 60%, #EF9F27)',
            }}
          />
        )}
      </div>
      {/* breakdown */}
      {scores && (
        <div data-testid="quality-breakdown" style={{ display: 'flex', gap: 10, fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>
          <span>規格驗證 <strong style={{ color: 'var(--ink-2, rgba(238,236,234,0.7))' }}>{scores.validation.totalScore}</strong></span>
          <span>·</span>
          <span>實作品質 <strong style={{ color: 'var(--ink-2, rgba(238,236,234,0.7))' }}>{scores.implementation.totalScore}</strong></span>
          <span>·</span>
          <span>觸發能力 <strong style={{ color: 'var(--ink-2, rgba(238,236,234,0.7))' }}>{scores.activation.totalScore}</strong></span>
        </div>
      )}
    </div>
  )
}
