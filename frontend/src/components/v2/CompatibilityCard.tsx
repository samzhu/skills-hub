import type { Skill } from '@/types/skill'

interface Props {
  skill: Skill
}

export function CompatibilityCard({ skill }: Props) {
  const chips = skill.compatibility ?? []

  return (
    <div data-testid="compat-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        相容性
      </div>
      {chips.length === 0 ? (
        <span style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>—</span>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {chips.map(c => (
            <span
              key={c}
              className="chip"
              style={{
                fontSize: 10,
                padding: '2px 8px',
                borderRadius: 4,
                background: 'rgba(127,119,221,0.12)',
                color: '#C9C5F2',
                fontFamily: 'monospace',
              }}
            >
              {c}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
