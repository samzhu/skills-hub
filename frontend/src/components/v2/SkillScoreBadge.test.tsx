import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SkillScoreBadge } from './SkillScoreBadge'

describe('SkillScoreBadge', () => {
  it('AC-S142a-4: skillScore=89 → arc dashoffset = 314.16 × (1-89/100) + 26.2 = 60.76', () => {
    const { container } = render(<SkillScoreBadge skillScore={89} />)
    const arc = container.querySelector('[data-testid="score-arc"]') as SVGCircleElement
    expect(arc).toBeTruthy()
    const offset = parseFloat(arc.getAttribute('data-dashoffset') ?? '0')
    expect(offset).toBeCloseTo(60.76, 1)
    expect(screen.getByText('89')).toBeTruthy()
    expect(screen.getByText('/100')).toBeTruthy()
    expect(screen.getByText('技能分數')).toBeTruthy()
  })

  it('AC-S142a-5: skillScore=null → center 顯「—」+ 下方「評分計算中」; no fill arc', () => {
    const { container } = render(<SkillScoreBadge skillScore={null} />)
    expect(screen.getByText('—')).toBeTruthy()
    expect(screen.getByText('評分計算中')).toBeTruthy()
    expect(container.querySelector('[data-testid="score-arc"]')).toBeNull()
  })
})
