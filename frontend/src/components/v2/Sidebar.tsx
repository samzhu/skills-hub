import type { Skill, SkillVersion } from '@/types/skill'
import type { SkillScores } from '@/api/scores'
import type { SecurityReport } from '@/api/security'
import { InstallCard } from './InstallCard'
import { SparklineCard } from './SparklineCard'
import { DetailsCard } from './DetailsCard'
import { CompatibilityCard } from './CompatibilityCard'
import { VersionHistoryMini } from './VersionHistoryMini'
import { QualityTOCCard } from './QualityTOCCard'
import { ReproducibilityCard } from './ReproducibilityCard'
import { SecurityAuditCard } from './SecurityAuditCard'

interface Props {
  skill: Skill
  version: SkillVersion | undefined
  stats: number[]
  scores: SkillScores | null | undefined
  report: SecurityReport | null | undefined
  versions: SkillVersion[]
  activeTab: string
  onTabChange: (tab: string) => void
}

export function Sidebar({ skill, version, stats, scores, report, versions, activeTab, onTabChange }: Props) {
  return (
    <div
      data-testid="sidebar"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 14,
        borderLeft: '0.5px solid var(--line, rgba(255,255,255,0.08))',
        paddingLeft: 22,
      }}
    >
      {/* Always-visible cards */}
      <InstallCard skill={skill} />
      <SparklineCard stats={stats} />
      <DetailsCard skill={skill} version={version} />
      <CompatibilityCard skill={skill} />
      <VersionHistoryMini versions={versions} onTabChange={onTabChange} />

      {/* Contextual: Quality tab */}
      {activeTab === 'quality' && (
        <>
          <QualityTOCCard />
          {scores && <ReproducibilityCard scores={scores} />}
        </>
      )}

      {/* Contextual: Security tab */}
      {activeTab === 'security' && report && (
        <SecurityAuditCard report={report} />
      )}
    </div>
  )
}
