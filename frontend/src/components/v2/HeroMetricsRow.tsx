import type { SkillScores } from '@/api/scores'
import type { SecurityReport } from '@/api/security'
import { SkillScoreBadge } from './SkillScoreBadge'
import { QualityHeroCard } from './QualityHeroCard'
import { SecurityHeroCard } from './SecurityHeroCard'

interface Props {
  skillScore: number | null
  scores: SkillScores | null | undefined
  report: SecurityReport | null | undefined
  activeTab: string
  onTabChange: (tab: string) => void
}

/**
 * S142a — Three-card hero metrics row (SkillScore | Quality | Security).
 * grid-template-columns: 160px 1fr 1fr (per AC-S142a-6).
 */
export function HeroMetricsRow({ skillScore, scores, report, activeTab, onTabChange }: Props) {
  return (
    <div
      data-testid="hero-metrics-row"
      style={{
        display: 'grid',
        gridTemplateColumns: '160px 1fr 1fr',
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
        report={report}
        active={activeTab === 'security'}
        onClick={() => onTabChange('security')}
      />
    </div>
  )
}
