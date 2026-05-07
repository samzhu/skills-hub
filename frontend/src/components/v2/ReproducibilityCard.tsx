import type { SkillScores } from '@/api/scores'

interface Props {
  scores: SkillScores
}

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '1 天前'
  return `${days} 天前`
}

export function ReproducibilityCard({ scores }: Props) {
  const rows = [
    { label: 'Evaluated', value: relativeTime(scores.evaluatedAt), mono: false },
    { label: 'Evaluator', value: 'gemini-2.5-flash', mono: true },
    { label: 'Prompt ver', value: scores.evaluatorVersion, mono: true },
    { label: 'Skill ver', value: scores.skillVersion, mono: true },
  ]

  return (
    <div data-testid="reproducibility-card" style={{ padding: '14px 16px', background: 'rgba(0,0,0,0.2)', borderRadius: 10 }}>
      <div style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))', marginBottom: 10 }}>
        評分可重現性
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {rows.map(r => (
          <div key={r.label} style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 11, color: 'var(--ink-3, rgba(238,236,234,0.4))' }}>{r.label}</span>
            <span style={{ fontSize: 11, fontFamily: r.mono ? 'monospace' : undefined }}>{r.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
