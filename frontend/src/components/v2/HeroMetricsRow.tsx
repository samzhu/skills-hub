import type { SkillScores } from '@/api/scores'
import type { RiskLevel } from '@/types/skill'
import { SkillScoreBadge } from './SkillScoreBadge'
import { QualityHeroCard } from './QualityHeroCard'
import { SecurityHeroCard } from './SecurityHeroCard'

interface Props {
  skillScore: number | null
  scores: SkillScores | null | undefined
  riskLevel: RiskLevel | null | undefined
  activeTab: string
  onTabChange: (tab: string) => void
}

/**
 * S142a/S172-T06 — Hero metrics row (SkillScore | Quality | Security).
 */
export function HeroMetricsRow({ skillScore, scores, riskLevel, activeTab, onTabChange }: Props) {
  return (
    <div
      data-testid="hero-metrics-row"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 220px), 1fr))',
        gap: 10,
        marginBottom: 0,
      }}
    >
      <SkillScoreBadge skillScore={skillScore} />
      <QualityHeroCard
        scores={scores}
        active={activeTab === 'quality'}
        onClick={() => onTabChange('quality')}
      />
      <SecurityHeroCard
        riskLevel={riskLevel}
        active={activeTab === 'security'}
        onClick={() => onTabChange('security')}
      />
    </div>
  )
}
