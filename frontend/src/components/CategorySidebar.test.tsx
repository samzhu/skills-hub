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
  it('AC-1: renders 全部 button + each category', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    expect(screen.getByText('全部')).toBeInTheDocument()
    expect(screen.getByText('devops')).toBeInTheDocument()
    expect(screen.getByText('testing')).toBeInTheDocument()
    expect(screen.getByText('security')).toBeInTheDocument()
  })

  it('AC-2: 全部 count = sum of all category counts', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    const allBtn = screen.getByText('全部').closest('button')
    expect(allBtn?.textContent).toContain('20') // 12+5+3
  })

  it('AC-3: each category renders its own count', () => {
    render(<CategorySidebar categories={cats} selected={null} onSelect={vi.fn()} />)
    const devopsBtn = screen.getByText('devops').closest('button')
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
    const testingBtn = screen.getByText('testing').closest('button')
    expect(testingBtn?.className).toContain('bg-accent')
  })

  it('AC-6: click 全部 → onSelect(null)', () => {
    const onSelect = vi.fn()
    render(<CategorySidebar categories={cats} selected="testing" onSelect={onSelect} />)
    fireEvent.click(screen.getByText('全部'))
    expect(onSelect).toHaveBeenCalledWith(null)
  })

  it('AC-7: click on category → onSelect(name)', () => {
    const onSelect = vi.fn()
    render(<CategorySidebar categories={cats} selected={null} onSelect={onSelect} />)
    fireEvent.click(screen.getByText('security'))
    expect(onSelect).toHaveBeenCalledWith('security')
  })
})
