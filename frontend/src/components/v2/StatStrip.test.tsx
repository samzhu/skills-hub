import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { StatStrip } from './StatStrip'
import type { Skill } from '@/types/skill'

const baseSkill: Skill = {
  id: 's1', name: 'test', description: '', author: 'alice', category: 'AI',
  latestVersion: '1.0.0', riskLevel: 'LOW', status: 'PUBLISHED',
  visibility: 'PUBLIC',
  downloadCount: 1284, averageRating: 4.8, reviewCount: 42,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-05-04T00:00:00Z',
  verified: true, latestVersionPublishedAt: '2026-05-04T00:00:00Z',
  license: 'MIT', compatibility: [], versionCount: 7, openFlagCount: 0,
}

// 14-day stats: last week sum=500, this week sum=590 → delta ≈ +18%
const stats14d = [70, 70, 70, 70, 75, 75, 70, 80, 85, 85, 85, 85, 85, 85]

describe('StatStrip', () => {
  it('AC-S142a-10: 4 cells rendered', () => {
    render(<StatStrip skill={baseSkill} stats={stats14d} />)
    expect(screen.getByText('下載次數')).toBeTruthy()
    expect(screen.getByText('評分')).toBeTruthy()
    expect(screen.getByText('版本數')).toBeTruthy()
    expect(screen.getByText('待處理旗標')).toBeTruthy()
  })

  it('AC-S142a-10: downloads delta ↑ with green color', () => {
    render(<StatStrip skill={baseSkill} stats={stats14d} />)
    const delta = screen.getByTestId('download-delta')
    expect(delta.textContent).toMatch(/↑.*%/)
    expect(delta.style.color).toContain('var(--green-text')
  })

  it('AC-S142a-10: negative delta → ↓ with red color', () => {
    // this week lower than last week
    const downStats = [85, 85, 85, 85, 85, 85, 85, 50, 50, 50, 50, 50, 50, 50]
    render(<StatStrip skill={baseSkill} stats={downStats} />)
    const delta = screen.getByTestId('download-delta')
    expect(delta.textContent).toMatch(/↓.*%/)
    expect(delta.style.color).toContain('var(--red-text')
  })

  it('AC-S142a-10: versionCount shows from skill', () => {
    render(<StatStrip skill={baseSkill} stats={stats14d} />)
    expect(screen.getByTestId('version-count').textContent).toBe('7')
  })

  it('AC-S142a-10: openFlagCount=0 → 無紅色', () => {
    render(<StatStrip skill={baseSkill} stats={stats14d} />)
    const flags = screen.getByTestId('open-flags')
    expect(flags.textContent).toBe('0')
    expect(flags.style.color).not.toContain('red')
  })

  it('AC-S142a-10: openFlagCount > 0 → 紅色數字', () => {
    const skill = { ...baseSkill, openFlagCount: 3 }
    render(<StatStrip skill={skill} stats={stats14d} />)
    const flags = screen.getByTestId('open-flags')
    expect(flags.textContent).toBe('3')
    expect(flags.style.color).toContain('var(--red-text')
  })
})
