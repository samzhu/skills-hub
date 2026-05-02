import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MetricCard } from './MetricCard'

/**
 * MetricCard tests — shallow render with prop pass-through。
 */

describe('MetricCard', () => {
  it('AC-1: renders label + value', () => {
    render(<MetricCard label="下載次數" value="1,234" />)
    expect(screen.getByText('下載次數')).toBeInTheDocument()
    expect(screen.getByText('1,234')).toBeInTheDocument()
  })

  it('AC-2: number value renders as text', () => {
    render(<MetricCard label="N" value={42} />)
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('AC-3: subtitle renders when provided', () => {
    render(<MetricCard label="x" value="y" subtitle="for last 30 days" />)
    expect(screen.getByText('for last 30 days')).toBeInTheDocument()
  })

  it('AC-4: subtitle omitted when not provided (no extra <p>)', () => {
    const { container } = render(<MetricCard label="x" value="y" />)
    // 結構應有 2 個 p (label + value)，沒 subtitle
    expect(container.querySelectorAll('p').length).toBe(2)
  })
})
