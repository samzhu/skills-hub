import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { IntentSummaryCard } from './IntentSummaryCard'

/**
 * IntentSummaryCard tests — S094b semantic search intent summary。
 * Concepts.length=0 → 不顯 chips; >0 顯各 concept chip。
 */

describe('IntentSummaryCard — S094b', () => {
  it('AC-1: renders 已理解你的意圖 label', () => {
    render(<IntentSummaryCard summary="user wants to convert dates" concepts={[]} />)
    expect(screen.getByText('已理解你的意圖')).toBeInTheDocument()
  })

  it('AC-2: renders summary text', () => {
    render(<IntentSummaryCard summary="convert ISO 8601 to epoch" concepts={[]} />)
    expect(screen.getByText('convert ISO 8601 to epoch')).toBeInTheDocument()
  })

  it('AC-3: empty concepts array → no concept chips rendered', () => {
    const { container } = render(<IntentSummaryCard summary="x" concepts={[]} />)
    // 沒 concept chips → 應只有 label + summary 兩段文字結構
    expect(screen.queryByText('docker')).not.toBeInTheDocument()
  })

  it('AC-4: concepts > 0 → renders each concept as chip', () => {
    render(
      <IntentSummaryCard
        summary="x"
        concepts={['docker', 'compose', 'orchestration']}
      />,
    )
    expect(screen.getByText('docker')).toBeInTheDocument()
    expect(screen.getByText('compose')).toBeInTheDocument()
    expect(screen.getByText('orchestration')).toBeInTheDocument()
  })
})
