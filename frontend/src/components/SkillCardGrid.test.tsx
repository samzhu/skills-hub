import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { SkillCardGrid } from './SkillCardGrid'
import type { ReactElement } from 'react'

function renderGrid(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>)
}

describe('SkillCardGrid — S172 empty search actions', () => {
  it('AC-S172-6: zero search result clear action calls onClearQuery', () => {
    const onClearQuery = vi.fn()
    renderGrid(<SkillCardGrid skills={[]} query="docker" onClearQuery={onClearQuery} />)

    fireEvent.click(screen.getByRole('button', { name: /清除關鍵字並瀏覽全部技能/ }))

    expect(onClearQuery).toHaveBeenCalledTimes(1)
  })

  it('AC-S172-6: zero search result does not show semantic mode promise without control', () => {
    renderGrid(<SkillCardGrid skills={[]} query="docker" onClearQuery={vi.fn()} />)

    expect(screen.queryByText('切換到語意搜尋模式')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /發布你自己的技能/ })).toHaveAttribute('href', '/publish')
  })
})
