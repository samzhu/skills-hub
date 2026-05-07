import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { QualityHeroCard } from './QualityHeroCard'
import type { SkillScores } from '@/api/scores'

const mockScores: SkillScores = {
  skillId: 's1',
  skillVersionId: 'v1',
  skillVersion: '1.0.0',
  evaluatedAt: '2026-05-07T00:00:00Z',
  evaluatorVersion: 'v1',
  validation:     { totalScore: 100, dimensions: {} },
  implementation: { totalScore: 85,  dimensions: {} },
  activation:     { totalScore: 92,  dimensions: {} },
  total: 92,
  skillScore: 89,
}

describe('QualityHeroCard', () => {
  it('AC-S142a-6: 已評分顯示 92% + Validation 100 / Implementation 85 / Discovery 92', () => {
    render(<QualityHeroCard scores={mockScores} active={false} onClick={vi.fn()} />)
    expect(screen.getByTestId('quality-pct').textContent).toBe('92%')
    expect(screen.getByTestId('quality-breakdown').textContent).toContain('100')
    expect(screen.getByTestId('quality-breakdown').textContent).toContain('85')
    expect(screen.getByTestId('quality-breakdown').textContent).toContain('92')
  })

  it('AC-S142a-7: active=true → border accent; active=false → default border', () => {
    const { rerender, getByTestId } = render(
      <QualityHeroCard scores={mockScores} active={true} onClick={vi.fn()} />
    )
    // JSDOM normalizes rgba shorthand — check key components (127, 119, 221)
    expect(getByTestId('quality-hero-card').style.border).toContain('127')
    rerender(<QualityHeroCard scores={mockScores} active={false} onClick={vi.fn()} />)
    expect(getByTestId('quality-hero-card').style.border).not.toContain('127, 119')
  })

  it('AC-S142a-7: click 觸發 onClick', () => {
    const onClick = vi.fn()
    render(<QualityHeroCard scores={mockScores} active={false} onClick={onClick} />)
    fireEvent.click(screen.getByTestId('quality-hero-card'))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('scores=null → 顯「—」+ 評分計算中', () => {
    render(<QualityHeroCard scores={null} active={false} onClick={vi.fn()} />)
    expect(screen.getByTestId('quality-pct').textContent).toBe('—')
    expect(screen.getByText('評分計算中')).toBeTruthy()
  })
})
