import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { HeroMetricsRow } from './HeroMetricsRow'

describe('HeroMetricsRow', () => {
  it('AC-S142a-6: renders responsive 3-card grid', () => {
    const { getByTestId } = render(
      <HeroMetricsRow
        skillScore={89}
        scores={null}
        riskLevel={null}
        activeTab="overview"
        onTabChange={vi.fn()}
      />
    )
    const row = getByTestId('hero-metrics-row')
    expect(row.style.gridTemplateColumns).toBe('repeat(auto-fit, minmax(min(100%, 220px), 1fr))')
    expect(screen.getByTestId('skill-score-badge')).toBeTruthy()
    expect(screen.getByTestId('quality-hero-card')).toBeTruthy()
    expect(screen.getByTestId('security-hero-card')).toBeTruthy()
  })

  it('AC-S142a-7: Quality tab active when activeTab=quality', () => {
    render(
      <HeroMetricsRow
        skillScore={89}
        scores={null}
        riskLevel={null}
        activeTab="quality"
        onTabChange={vi.fn()}
      />
    )
    // JSDOM normalizes rgba — check 127, 119, 221 components present
    expect(screen.getByTestId('quality-hero-card').style.border).toContain('127')
  })

  it('AC-S183-3: HeroMetricsRow 將 LOW riskLevel 傳給 SecurityHeroCard', () => {
    render(
      <HeroMetricsRow
        skillScore={89}
        scores={null}
        riskLevel="LOW"
        activeTab="overview"
        onTabChange={vi.fn()}
      />
    )

    expect(screen.getByTestId('security-value').textContent).toBe('低風險')
    expect(screen.getByText('3 個綠燈 · 1 個紅燈')).toBeTruthy()
  })
})
