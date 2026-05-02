import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RiskBadge } from './RiskBadge'

/**
 * RiskBadge tests — 4-tier rendering invariants per S096c。
 * 對齊 docs/grimo/test-cases.md Round 5（risk tier 系統）。
 *
 * 不測 inline-style hex（per ALWAYS rule「test against DOM structure...
 * not against incidental constants colors/spacing/IDs」）— 只測 label
 * + null 與 unknown tier 的 fallback 行為。
 */

describe('RiskBadge — S096c 4-tier', () => {
  it('AC-1: NONE renders 無風險 label', () => {
    render(<RiskBadge level="NONE" />)
    expect(screen.getByText('無風險')).toBeInTheDocument()
  })

  it('AC-2: LOW renders 低風險 label', () => {
    render(<RiskBadge level="LOW" />)
    expect(screen.getByText('低風險')).toBeInTheDocument()
  })

  it('AC-3: MEDIUM renders 中風險 label', () => {
    render(<RiskBadge level="MEDIUM" />)
    expect(screen.getByText('中風險')).toBeInTheDocument()
  })

  it('AC-4: HIGH renders 高風險 label', () => {
    render(<RiskBadge level="HIGH" />)
    expect(screen.getByText('高風險')).toBeInTheDocument()
  })

  it('AC-5: null level renders 未評估 fallback', () => {
    render(<RiskBadge level={null} />)
    expect(screen.getByText('未評估')).toBeInTheDocument()
  })

  it('AC-6: unknown tier (e.g. CRITICAL future) renders raw level (graceful fallback)', () => {
    render(<RiskBadge level="CRITICAL" />)
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  })

  it('AC-7: NONE tier 含 caveat tooltip — 「不代表 100% 安全」', () => {
    render(<RiskBadge level="NONE" />)
    const badge = screen.getByText('無風險')
    expect(badge.getAttribute('title')).toContain('不代表 100% 安全')
  })
})
