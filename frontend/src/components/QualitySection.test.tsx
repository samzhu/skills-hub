import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QualitySection } from './QualitySection'
import type { SkillScores } from '@/api/scores'

const mockScores: SkillScores = {
  skillId: 'skill-1',
  skillVersionId: 'ver-1',
  skillVersion: '1.0.0',
  evaluatedAt: '2026-05-06T00:00:00Z',
  evaluatorVersion: 'gemini-2.5-flash@v1',
  validation:     { totalScore: 100, dimensions: {} },
  implementation: { totalScore: 85,  dimensions: {} },
  activation:     { totalScore: 92,  dimensions: {} },
  total: 91,
  skillScore: null,
}

describe('QualitySection', () => {
  it('AC-5: loading 狀態顯示 skeleton（undefined scores）', () => {
    const { container } = render(
      <QualitySection scores={undefined} riskLevel="LOW" />
    )
    expect(container.querySelector('.animate-pulse')).toBeTruthy()
    expect(screen.queryByText(/91%/)).toBeNull()
  })

  it('AC-3: 未評分 null 顯示 fallback 文字', () => {
    render(<QualitySection scores={null} riskLevel="LOW" />)
    expect(screen.getByText(/評分計算中/)).toBeTruthy()
  })

  it('AC-1: 評分完成顯示 total% + 安全等級', () => {
    render(<QualitySection scores={mockScores} riskLevel="LOW" />)
    expect(screen.getByText('91%')).toBeTruthy()
    expect(screen.getByText('低風險')).toBeTruthy()
  })

  it('AC-1: riskLevel=HIGH 顯示高風險', () => {
    render(<QualitySection scores={mockScores} riskLevel="HIGH" />)
    expect(screen.getByText('高風險')).toBeTruthy()
  })

  it('AC-3: riskLevel=null 顯示「評估中」', () => {
    render(<QualitySection scores={mockScores} riskLevel={null} />)
    expect(screen.getByText('評估中')).toBeTruthy()
  })
})
