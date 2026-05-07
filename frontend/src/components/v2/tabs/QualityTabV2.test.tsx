import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QualityTabV2 } from './QualityTabV2'
import type { SkillScores } from '@/api/scores'

const mockScores: SkillScores = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  evaluatedAt: '2026-05-07T00:00:00Z', evaluatorVersion: 'v1',
  validation: {
    totalScore: 100,
    dimensions: {
      schemaCompliance: { score: 3, reasoning: 'Schema is fully compliant with the spec.' },
      descriptionQuality: { score: 2, reasoning: 'Description could be more detailed.' },
    },
  },
  implementation: { totalScore: 85, dimensions: {} },
  activation: { totalScore: 92, dimensions: {} },
  total: 92,
  skillScore: 89,
}

describe('QualityTabV2', () => {
  it('AC-S142a-12: 3 sections Validation / Implementation / Discovery rendered', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('規格驗證')).toBeTruthy()
    expect(screen.getByText('實作品質')).toBeTruthy()
    expect(screen.getByText('觸發能力')).toBeTruthy()
  })

  it('AC-S142a-12: ScoreDot rendered per dimension', () => {
    const { container } = render(<QualityTabV2 scores={mockScores} />)
    // 2 dimensions in validation section → 2 ScoreDots
    expect(container.querySelectorAll('[data-testid="score-dot"]').length).toBeGreaterThanOrEqual(2)
  })

  it('AC-S142a-12: reasoning 預設展開，Show less button visible', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('Schema is fully compliant with the spec.')).toBeTruthy()
    expect(screen.getAllByText('Show less').length).toBeGreaterThan(0)
  })

  it('AC-S142a-12: click Show less → Show more toggle', () => {
    render(<QualityTabV2 scores={mockScores} />)
    const toggleBtn = screen.getAllByText('Show less')[0]
    fireEvent.click(toggleBtn)
    expect(screen.getAllByText('Show more').length).toBeGreaterThan(0)
  })

  it('scores=null → 此版本尚未評分 fallback', () => {
    render(<QualityTabV2 scores={null} />)
    expect(screen.getByTestId('quality-tab-empty')).toBeTruthy()
    expect(screen.getByText('此版本尚未評分')).toBeTruthy()
  })

  it('scores=undefined → loading skeleton', () => {
    const { container } = render(<QualityTabV2 scores={undefined} />)
    expect(container.querySelector('.animate-pulse')).toBeTruthy()
  })
})
