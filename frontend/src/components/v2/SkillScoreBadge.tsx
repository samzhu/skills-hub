/**
 * S142a — SVG hexagon ring badge for SkillScore.
 * Arc formula (per BDD §AC-S142a-4):
 *   dashoffset = 314.16 × (1 - score/100) + 26.2
 * Circumference r=50 = 2π×50 = 314.16; transform="rotate(135 60 60)".
 */
export function SkillScoreBadge({ skillScore }: { skillScore: number | null }) {
  const C = 314.16
  const haScore = skillScore !== null && skillScore !== undefined
  const dashOffset = haScore ? C * (1 - skillScore / 100) + 26.2 : C

  return (
    <div
      data-testid="skill-score-badge"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 4,
        width: 160,
        padding: '14px 16px',
        background: 'var(--bg-2, rgba(255,255,255,0.04))',
        border: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        borderRadius: 16,
      }}
    >
      <svg width="120" height="120" viewBox="0 0 120 120" aria-label="Skill score ring">
        <defs>
          <linearGradient id="score-grad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#7F77DD" />
            <stop offset="55%" stopColor="#D9388A" />
            <stop offset="100%" stopColor="#EF9F27" />
          </linearGradient>
        </defs>
        {/* inner glow */}
        <circle cx="60" cy="60" r="42" fill="rgba(127,119,221,0.06)" />
        {/* track */}
        <circle
          cx="60" cy="60" r="50"
          stroke="rgba(127,119,221,0.12)"
          strokeWidth="7"
          strokeDasharray={C}
          strokeDashoffset="78.54"
          fill="none"
          transform="rotate(135 60 60)"
        />
        {/* fill arc */}
        {haScore && (
          <circle
            cx="60" cy="60" r="50"
            stroke="url(#score-grad)"
            strokeWidth="7"
            strokeLinecap="round"
            strokeDasharray={C}
            strokeDashoffset={dashOffset}
            fill="none"
            transform="rotate(135 60 60)"
            data-testid="score-arc"
            data-dashoffset={dashOffset}
          />
        )}
        {/* labels */}
        <text x="60" y="42" textAnchor="middle" fontSize="8" fontWeight="600"
          letterSpacing="1.5" fill="rgba(127,119,221,0.9)">
          SKILL SCORE
        </text>
        <text x="60" y="70" textAnchor="middle" fontSize="30" fontWeight="600"
          letterSpacing="-1" fill="#EEECEA">
          {haScore ? skillScore : '—'}
        </text>
        <text x="60" y="84" textAnchor="middle" fontSize="10"
          fill="rgba(238,236,234,0.35)">
          {haScore ? '/100' : '評分計算中'}
        </text>
      </svg>
    </div>
  )
}
