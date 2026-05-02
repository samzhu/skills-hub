import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { Sparkline } from './Sparkline'

describe('Sparkline — S096d3', () => {
  it('AC-1: renders SVG polyline for non-empty data', () => {
    const { container } = render(<Sparkline data={[1, 2, 3, 4, 5]} />)
    const svg = container.querySelector('svg')
    expect(svg).toBeTruthy()
    const polyline = container.querySelector('polyline')
    expect(polyline).toBeTruthy()
    // 5 points = 5 "x,y" pairs separated by whitespace
    const points = polyline!.getAttribute('points') ?? ''
    expect(points.split(/\s+/).filter(Boolean)).toHaveLength(5)
  })

  it('AC-2: empty array shows dash placeholder (no SVG)', () => {
    const { container } = render(<Sparkline data={[]} />)
    expect(container.querySelector('svg')).toBeNull()
    expect(container.textContent).toContain('—')
  })

  it('AC-3: flat-zero data renders polyline at bottom (max=1 防 0 division)', () => {
    const { container } = render(<Sparkline data={[0, 0, 0, 0]} />)
    const polyline = container.querySelector('polyline')
    expect(polyline).toBeTruthy()
    // 全 0 + max=1 fallback → 所有 y = height-(0/1)*height = height（底部基準線）
    const points = polyline!.getAttribute('points') ?? ''
    const yValues = points.split(/\s+/).map((p) => Number(p.split(',')[1]))
    yValues.forEach((y) => expect(y).toBeCloseTo(18, 1)) // default height = 18
  })

  it('AC-4: max value scales to top of chart', () => {
    const { container } = render(<Sparkline data={[1, 5]} height={20} />)
    const polyline = container.querySelector('polyline')
    const points = polyline!.getAttribute('points') ?? ''
    // Second point (max=5) → y = 20 - (5/5)*20 = 0 (top)
    const [, secondPoint] = points.split(/\s+/)
    const y = Number(secondPoint.split(',')[1])
    expect(y).toBeCloseTo(0, 1)
  })

  it('AC-5: respects custom width/height/color props', () => {
    const { container } = render(<Sparkline data={[1, 2, 3]} width={100} height={40} color="#FF0000" />)
    const svg = container.querySelector('svg')
    expect(svg?.getAttribute('width')).toBe('100')
    expect(svg?.getAttribute('height')).toBe('40')
    const polyline = container.querySelector('polyline')
    expect(polyline?.getAttribute('stroke')).toBe('#FF0000')
  })
})
