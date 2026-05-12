import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CategorySidebar } from './CategorySidebar'

/**
 * CategorySidebar tests — categories list + selected state + onSelect callback。
 */

const cats = [
  { name: 'devops', count: 12 },
  { name: 'testing', count: 5 },
  { name: 'security', count: 3 },
]

describe('CategorySidebar', () => {
  it('AC-1: renders 全部 button + each category（S159b: lowercase fixture → capitalize display）', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    expect(screen.getByText('全部')).toBeInTheDocument()
    // DB V20 後 cat.name 是 lowercase；display 透過 capitalize helper 還原
    expect(screen.getByText('Devops')).toBeInTheDocument()
    expect(screen.getByText('Testing')).toBeInTheDocument()
    expect(screen.getByText('Security')).toBeInTheDocument()
  })

  it('AC-2: 全部 count = sum of all category counts', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    const allBtn = screen.getByText('全部').closest('button')
    expect(allBtn?.textContent).toContain('20') // 12+5+3
  })

  it('AC-3: each category renders its own count', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    const devopsBtn = screen.getByText('Devops').closest('button')
    expect(devopsBtn?.textContent).toContain('12')
  })

  it('AC-4: selected=null → 全部 active state', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    const allBtn = screen.getByText('全部').closest('button')
    expect(allBtn?.className).toContain('bg-accent')
    expect(allBtn?.className).toContain('font-medium')
  })

  it('AC-5: selected=cat name → that cat active', () => {
    render(<CategorySidebar categories={cats} selected="testing" onSelect={vi.fn()} />)
    const testingBtn = screen.getByText('Testing').closest('button')
    expect(testingBtn?.className).toContain('bg-accent')
  })

  it('AC-6: click 全部 → onSelect(null)', () => {
    const onSelect = vi.fn()
    render(<CategorySidebar categories={cats} selected="testing" onSelect={onSelect} />)
    fireEvent.click(screen.getByText('全部'))
    expect(onSelect).toHaveBeenCalledWith(null)
  })

  it('AC-7: click on category → onSelect(name) — callback 仍收 raw lowercase 值', () => {
    const onSelect = vi.fn()
    render(<CategorySidebar categories={cats} selected={null} onSelect={onSelect} />)
    fireEvent.click(screen.getByText('Security'))
    expect(onSelect).toHaveBeenCalledWith('security')  // callback 收 cat.name 原值（lowercase）
  })
})
