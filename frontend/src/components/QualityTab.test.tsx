import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QualityTab } from './QualityTab'
import type { SkillScores } from '@/api/scores'

const mockScores: SkillScores = {
  skillId: 'skill-1',
  skillVersionId: 'ver-1',
  skillVersion: '1.0.0',
  evaluatedAt: '2026-05-06T00:00:00Z',
  evaluatorVersion: 'gemini-2.5-flash@v1',
  validation: {
    totalScore: 100,
    dimensions: {
      lineCount: { score: 3, reasoning: '412 lines within 500 limit.' },
      bodyPresent: { score: 3, reasoning: 'Body section exists.' },
    },
  },
  implementation: {
    totalScore: 85,
    dimensions: {
      conciseness: { score: 2, reasoning: 'Could be more concise.' },
    },
  },
  activation: {
    totalScore: 92,
    dimensions: {
      specificity: { score: 3, reasoning: 'Clear trigger conditions.' },
    },
  },
  total: 91,
}

describe('QualityTab', () => {
  it('AC-5: loading 時顯示 skeleton（undefined）', () => {
    const { container } = render(<QualityTab scores={undefined} />)
    expect(container.querySelector('.animate-pulse')).toBeTruthy()
  })

  it('AC-3: 未評分 null 顯示 fallback 訊息', () => {
    render(<QualityTab scores={null} />)
    expect(screen.getByText(/尚未評分/)).toBeTruthy()
  })

  it('AC-2: 評分完成顯示 3 個 axis 標題', () => {
    render(<QualityTab scores={mockScores} />)
    expect(screen.getByText('規格驗證')).toBeTruthy()
    expect(screen.getByText('實作品質')).toBeTruthy()
    expect(screen.getByText('觸發能力')).toBeTruthy()
  })

  it('AC-2: 顯示 dimension 名稱與 reasoning', () => {
    render(<QualityTab scores={mockScores} />)
    expect(screen.getByText(/412 lines within 500 limit/)).toBeTruthy()
    expect(screen.getByText(/Could be more concise/)).toBeTruthy()
  })

  it('AC-2: 顯示評分 totalScore', () => {
    render(<QualityTab scores={mockScores} />)
    expect(screen.getByText('100%')).toBeTruthy()
    expect(screen.getByText('85%')).toBeTruthy()
    expect(screen.getByText('92%')).toBeTruthy()
  })
})
