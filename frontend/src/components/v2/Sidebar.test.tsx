import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { Sidebar } from './Sidebar'
import type { Skill, SkillVersion } from '@/types/skill'
import type { SkillScores } from '@/api/scores'
import type { SecurityReport } from '@/api/security'

const baseSkill: Skill = {
  id: 's1', name: 'my-skill', description: 'A skill', author: 'alice',
  category: 'AI', latestVersion: '1.0.0', riskLevel: 'LOW',
  status: 'PUBLISHED', downloadCount: 0, averageRating: 0, reviewCount: 0,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  verified: false, latestVersionPublishedAt: '2026-05-01T00:00:00Z',
  license: 'MIT', compatibility: ['node', 'python'], versionCount: 2, openFlagCount: 0,
}

const baseVersion: SkillVersion = {
  id: 'v1', skillId: 's1', version: '1.0.0',
  fileSize: 2048, fileCount: 5, publishedAt: '2026-05-01T00:00:00Z',
}

const baseVersions: SkillVersion[] = [
  baseVersion,
  { ...baseVersion, id: 'v2', version: '0.9.0', publishedAt: '2026-04-01T00:00:00Z' },
]

const baseScores: SkillScores = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  evaluatedAt: '2026-05-05T00:00:00Z', evaluatorVersion: 'v1',
  validation: { totalScore: 90, dimensions: {} },
  implementation: { totalScore: 85, dimensions: {} },
  activation: { totalScore: 92, dimensions: {} },
  total: 89, skillScore: 89,
}

const baseReport: SecurityReport = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  scannedAt: '2026-05-07T00:00:00Z', engineVersion: 'v1.0', ruleSetVersion: '2026-05',
  overall: 'PASS',
  checks: {
    shell:   { status: 'PASS', detail: 'OK' },
    paths:   { status: 'PASS', detail: 'OK' },
    secrets: { status: 'PASS', detail: 'OK' },
    deps:    { status: 'PASS', detail: 'OK' },
  },
}

function renderSidebar(activeTab = 'overview', overrides: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  return render(
    <Sidebar
      skill={baseSkill}
      version={baseVersion}
      stats={Array(30).fill(10)}
      scores={baseScores}
      report={baseReport}
      versions={baseVersions}
      activeTab={activeTab}
      onTabChange={vi.fn()}
      {...overrides}
    />
  )
}

describe('Sidebar', () => {
  it('AC-S142a-15: 5 always-visible cards present on overview tab', () => {
    renderSidebar('overview')
    expect(screen.getByTestId('install-card')).toBeTruthy()
    expect(screen.getByTestId('sparkline-card')).toBeTruthy()
    expect(screen.getByTestId('details-card')).toBeTruthy()
    expect(screen.getByTestId('compat-card')).toBeTruthy()
    expect(screen.getByTestId('version-history-mini')).toBeTruthy()
  })

  it('AC-S142a-15: quality tab shows QualityTOCCard + ReproducibilityCard', () => {
    renderSidebar('quality')
    expect(screen.getByTestId('quality-toc-card')).toBeTruthy()
    expect(screen.getByTestId('reproducibility-card')).toBeTruthy()
  })

  it('AC-S142a-15: quality tab contextual cards hidden on security tab', () => {
    renderSidebar('security')
    expect(screen.queryByTestId('quality-toc-card')).toBeNull()
    expect(screen.queryByTestId('reproducibility-card')).toBeNull()
  })

  it('AC-S142a-15: security tab shows SecurityAuditCard', () => {
    renderSidebar('security')
    expect(screen.getByTestId('security-audit-card')).toBeTruthy()
  })

  it('AC-S142a-15: SecurityAuditCard hidden on overview tab', () => {
    renderSidebar('overview')
    expect(screen.queryByTestId('security-audit-card')).toBeNull()
  })

  it('AC-S142a-15: no contextual cards on files tab', () => {
    renderSidebar('files')
    expect(screen.queryByTestId('quality-toc-card')).toBeNull()
    expect(screen.queryByTestId('security-audit-card')).toBeNull()
  })

  it('AC-S142a-17: DetailsCard shows license', () => {
    renderSidebar()
    expect(screen.getByText('MIT')).toBeTruthy()
  })

  it('AC-S142a-17: CompatibilityCard renders chips', () => {
    renderSidebar()
    expect(screen.getByText('node')).toBeTruthy()
    expect(screen.getByText('python')).toBeTruthy()
  })

  it('AC-S142a-17: VersionHistoryMini shows latest badge on first version', () => {
    renderSidebar()
    expect(screen.getByText('latest')).toBeTruthy()
  })

  it('AC-S142a-17: "查看全部 →" calls onTabChange with "versions"', () => {
    const onTabChange = vi.fn()
    renderSidebar('overview', { onTabChange })
    fireEvent.click(screen.getByTestId('versions-all-link'))
    expect(onTabChange).toHaveBeenCalledWith('versions')
  })

  it('AC-S142a-17: QualityTOCCard shows 3 sections', () => {
    renderSidebar('quality')
    expect(screen.getByText('Validation')).toBeTruthy()
    expect(screen.getByText('Implementation')).toBeTruthy()
    expect(screen.getByText('Discovery')).toBeTruthy()
  })

  it('AC-S142a-17: SecurityAuditCard shows engine and rule set', () => {
    renderSidebar('security')
    expect(screen.getByText(/risk-scanner/)).toBeTruthy()
    expect(screen.getByText('2026-05')).toBeTruthy()
  })
})
