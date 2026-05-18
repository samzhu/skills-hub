import { render, screen, fireEvent, within } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QualityTabV2 } from './QualityTabV2'
import type { SkillScores } from '@/api/scores'

const mockScores: SkillScores = {
  skillId: 's1', skillVersionId: 'v1', skillVersion: '1.0.0',
  evaluatedAt: '2026-05-07T00:00:00Z', evaluatorVersion: 'v1',
  validation: {
    totalScore: 97,
    dimensions: {
      lineCount: { score: 100, reasoning: '136 / 500 lines.' },
      frontmatterOfficialFormat: { score: 80, reasoning: 'allowed-tools accepted but YAML list warning.' },
      warnings: ['frontmatter_official_format: allowed-tools uses YAML list'],
    },
  },
  implementation: {
    totalScore: 85,
    dimensions: {
      conciseness: { score: 3, reasoning: 'Concise enough for the registry.' },
      actionability: { score: 2, reasoning: 'Actionable but could name sharper triggers.' },
    },
  },
  activation: {
    totalScore: 67,
    dimensions: {
      distinctiveness: { score: 1, reasoning: 'Some overlap with existing skills.' },
      triggerTermQuality: { score: 0, reasoning: 'Trigger terms are missing.' },
    },
  },
  total: 83,
  skillScore: 89,
}

describe('QualityTabV2', () => {
  it('AC-S142a-12: 3 sections Validation / Implementation / Discovery rendered', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('規格驗證')).toBeTruthy()
    expect(screen.getByText('實作品質')).toBeTruthy()
    expect(screen.getByText('觸發能力')).toBeTruthy()
  })

  it('AC-S201-1: validation 100 renders 通過 100/100', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('Line Count')).toBeTruthy()
    expect(screen.getByText('通過 100/100')).toBeTruthy()
  })

  it('AC-S201-2: validation 80 renders 注意 80/100', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('Frontmatter Official Format')).toBeTruthy()
    expect(screen.getByText('注意 80/100')).toBeTruthy()
  })

  it('AC-S201-3: validation warnings render 提醒 row', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('Warnings')).toBeTruthy()
    expect(screen.getByText('提醒 1')).toBeTruthy()
    expect(screen.getByText('frontmatter_official_format: allowed-tools uses YAML list')).toBeTruthy()
    expect(screen.queryByText('undefined/3')).toBeNull()
  })

  it('AC-S201-4: implementation and activation render 0-3 status labels', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('滿分 3/3')).toBeTruthy()
    expect(screen.getByText('可接受 2/3')).toBeTruthy()
    expect(screen.getByText('偏弱 1/3')).toBeTruthy()
    expect(screen.getByText('缺失 0/3')).toBeTruthy()
  })

  it('AC-S201-5: warnings array never renders undefined score', () => {
    const { container } = render(<QualityTabV2 scores={mockScores} />)
    expect(container.querySelectorAll('[data-testid="score-dot"]').length).toBe(0)
    expect(screen.queryByText(/undefined/)).toBeNull()
  })

  it('AC-S201-6: axis total score and progress bar remain driven by totalScore', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(within(screen.getByTestId('axis-validation')).getByText('97')).toBeTruthy()
    expect(screen.getByTestId('axis-progress-validation')).toHaveStyle({ width: '97%' })
  })

  it('AC-S142a-12: reasoning 預設展開，顯示較少 button visible', () => {
    render(<QualityTabV2 scores={mockScores} />)
    expect(screen.getByText('136 / 500 lines.')).toBeTruthy()
    expect(screen.getAllByText('顯示較少').length).toBeGreaterThan(0)
  })

  it('AC-S142a-12: click 顯示較少 → 顯示更多 toggle', () => {
    render(<QualityTabV2 scores={mockScores} />)
    const toggleBtn = screen.getAllByText('顯示較少')[0]
    fireEvent.click(toggleBtn)
    expect(screen.getAllByText('顯示更多').length).toBeGreaterThan(0)
  })

  it('S151: scores=null → 「評分計算中，請稍後重新整理」(對齊 hero card 風格)', () => {
    render(<QualityTabV2 scores={null} />)
    expect(screen.getByTestId('quality-tab-empty')).toBeTruthy()
    expect(screen.getByText('評分計算中，請稍後重新整理')).toBeTruthy()
    // 不再顯舊版「此版本尚未評分」字面
    expect(screen.queryByText('此版本尚未評分')).toBeNull()
  })

  it('scores=undefined → loading skeleton', () => {
    const { container } = render(<QualityTabV2 scores={undefined} />)
    expect(container.querySelector('.animate-pulse')).toBeTruthy()
  })
})
