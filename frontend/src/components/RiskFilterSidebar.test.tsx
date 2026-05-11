import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RiskFilterSidebar } from './RiskFilterSidebar'
import type { RiskLevel, Skill } from '@/types/skill'

/**
 * RiskFilterSidebar tests — S098d2 client-side aggregation invariants
 * 對齊 docs/grimo/test-cases.md Round 3.3 / 3.4。
 *
 * Pure component test：props {skills, selected Set, onToggle, onClear}；
 * 驗 count breakdown derivation + button click 觸發正確 callback。
 */

const skill = (riskLevel: RiskLevel | null, id: string): Skill => ({
  id,
  name: `skill-${id}`,
  author: 'a',
  description: 'd',
  category: 'utility',
  riskLevel,
  status: 'PUBLISHED',
  downloadCount: 0,
  averageRating: 0,
  reviewCount: 0,
  latestVersion: '1.0.0',
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-01T00:00:00Z',
  verified: false,
  latestVersionPublishedAt: null,
  license: null,
  compatibility: [],
  versionCount: 1,
  openFlagCount: 0,
})

describe('RiskFilterSidebar — S098d2', () => {
  it('AC-1: count breakdown reflects skills by tier', () => {
    const skills = [
      skill('NONE', '1'),
      skill('LOW', '2'),
      skill('LOW', '3'),
      skill('MEDIUM', '4'),
      skill('HIGH', '5'),
      skill(null, '6'), // null 不計入任何 tier
    ]
    render(<RiskFilterSidebar items={skills} selected={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />)
    // 「全部」count = total skills.length
    const allBtn = screen.getByText('全部').closest('button')
    expect(allBtn?.textContent).toContain('6')
    // 各 tier counts
    const noneRow = screen.getByText('無風險').closest('button')
    expect(noneRow?.textContent).toContain('1')
    const lowRow = screen.getByText('低風險').closest('button')
    expect(lowRow?.textContent).toContain('2')
    const medRow = screen.getByText('中風險').closest('button')
    expect(medRow?.textContent).toContain('1')
    const highRow = screen.getByText('高風險').closest('button')
    expect(highRow?.textContent).toContain('1')
  })

  it('AC-2: empty Set 顯「全部」為 active state', () => {
    render(<RiskFilterSidebar items={[]} selected={new Set()} onToggle={vi.fn()} onClear={vi.fn()} />)
    // 「全部」按鈕在 hasFilter=false 時帶 bg-accent class —— 用 className 含 'bg-accent' 驗
    const allBtn = screen.getByText('全部').closest('button')
    expect(allBtn?.className).toContain('bg-accent')
  })

  it('AC-3: click on tier button calls onToggle with that tier', () => {
    const onToggle = vi.fn()
    render(<RiskFilterSidebar items={[]} selected={new Set()} onToggle={onToggle} onClear={vi.fn()} />)
    fireEvent.click(screen.getByText('低風險'))
    expect(onToggle).toHaveBeenCalledWith('LOW')
  })

  it('AC-4: click on 全部 calls onClear', () => {
    const onClear = vi.fn()
    render(<RiskFilterSidebar items={[]} selected={new Set(['LOW' as RiskLevel])} onToggle={vi.fn()} onClear={onClear} />)
    fireEvent.click(screen.getByText('全部'))
    expect(onClear).toHaveBeenCalledTimes(1)
  })

  it('AC-5: selected tier 顯 active state', () => {
    const selected = new Set<RiskLevel>(['HIGH'])
    render(<RiskFilterSidebar items={[]} selected={selected} onToggle={vi.fn()} onClear={vi.fn()} />)
    const highBtn = screen.getByText('高風險').closest('button')
    expect(highBtn?.className).toContain('bg-accent')
    expect(highBtn?.className).toContain('font-medium')
  })
})
