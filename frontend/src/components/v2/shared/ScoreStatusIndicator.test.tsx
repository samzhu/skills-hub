import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { DimensionValue } from '@/api/scores'
import { isDimensionScore, scoreStatus } from './scoreStatus'
import { ScoreStatusIndicator, WarningStatusIndicator } from './ScoreStatusIndicator'

describe('ScoreStatusIndicator', () => {
  it('AC-S201-1: validation 100 renders pass label', () => {
    expect(scoreStatus('validation', 100)).toEqual({ tone: 'pass', label: '通過 100/100' })

    render(<ScoreStatusIndicator axisKey="validation" score={100} />)

    expect(screen.getByText('通過 100/100')).toBeInTheDocument()
    expect(screen.getByTestId('score-status-dot')).toHaveStyle({
      width: '12px',
      height: '12px',
      background: '#1D9E75',
    })
  })

  it('AC-S201-2: validation 80 renders warning label', () => {
    expect(scoreStatus('validation', 80)).toEqual({ tone: 'warn', label: '注意 80/100' })

    render(<ScoreStatusIndicator axisKey="validation" score={80} />)

    expect(screen.getByText('注意 80/100')).toBeInTheDocument()
    expect(screen.getByTestId('score-status-dot')).toHaveStyle({ background: '#EF9F27' })
  })

  it('AC-S201-4: implementation and activation render 0-3 labels', () => {
    expect(scoreStatus('implementation', 3)).toEqual({ tone: 'pass', label: '滿分 3/3' })
    expect(scoreStatus('implementation', 2)).toEqual({ tone: 'warn', label: '可接受 2/3' })
    expect(scoreStatus('activation', 1)).toEqual({ tone: 'fail', label: '偏弱 1/3' })
    expect(scoreStatus('activation', 0)).toEqual({ tone: 'fail', label: '缺失 0/3' })
  })

  it('AC-S201-5: warnings array is not treated as DimensionScore', () => {
    const warningValue: DimensionValue = ['frontmatter_official_format: allowed-tools uses YAML list']
    const scoreValue: DimensionValue = { score: 100, reasoning: '136 / 500 lines' }

    expect(isDimensionScore(scoreValue)).toBe(true)
    expect(isDimensionScore(warningValue)).toBe(false)

    render(<WarningStatusIndicator count={warningValue.length} />)

    expect(screen.getByText('提醒 1')).toBeInTheDocument()
    expect(screen.getByTestId('score-status-dot')).toHaveStyle({ background: '#EF9F27' })
  })
})
