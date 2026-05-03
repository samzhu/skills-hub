import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RatingStars } from './RatingStars'

/**
 * S098e2-T03 — RatingStars unit tests。
 *
 * Readonly 模式驗 fill 比例；interactive 模式驗 click → onChange callback。
 */

describe('RatingStars (S098e2-T03)', () => {
  it('readonly: value=4 → 4 顆 filled + 1 顆 outline', () => {
    const { container } = render(<RatingStars value={4} />)
    // aria-label 包整個 span
    expect(screen.getByLabelText('評分 4 / 5')).toBeInTheDocument()
    // 5 顆 svg
    const stars = container.querySelectorAll('svg')
    expect(stars).toHaveLength(5)
    // 前 4 顆有 fill class，第 5 顆沒
    expect(stars[0].classList.toString()).toContain('fill-[#FAC775]')
    expect(stars[3].classList.toString()).toContain('fill-[#FAC775]')
    expect(stars[4].classList.toString()).not.toContain('fill-[#FAC775]')
  })

  it('readonly: 0 → 0 顆 filled', () => {
    const { container } = render(<RatingStars value={0} />)
    const stars = container.querySelectorAll('svg')
    stars.forEach((star) => {
      expect(star.classList.toString()).not.toContain('fill-[#FAC775]')
    })
  })

  it('readonly: 4.7 → round 至 5 顆 filled', () => {
    const { container } = render(<RatingStars value={4.7} />)
    const stars = container.querySelectorAll('svg')
    stars.forEach((star) => {
      expect(star.classList.toString()).toContain('fill-[#FAC775]')
    })
  })

  it('interactive: 點第 3 顆星觸發 onChange(3)', () => {
    const onChange = vi.fn()
    render(<RatingStars value={0} onChange={onChange} />)
    const buttons = screen.getAllByRole('radio')
    expect(buttons).toHaveLength(5)
    fireEvent.click(buttons[2]) // index 2 = 3 星
    expect(onChange).toHaveBeenCalledWith(3)
  })

  it('interactive: aria-checked 反映 value', () => {
    render(<RatingStars value={4} onChange={() => {}} />)
    const buttons = screen.getAllByRole('radio')
    // 只有第 4 顆 aria-checked=true（單選 radio group）
    expect(buttons[3]).toHaveAttribute('aria-checked', 'true')
    expect(buttons[0]).toHaveAttribute('aria-checked', 'false')
  })
})
