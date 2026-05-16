import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { SecurityHeroCard } from './SecurityHeroCard'

function lightState(index: number): string {
  return screen.getByTestId(`risk-light-${index}`).getAttribute('data-state') ?? ''
}

describe('SecurityHeroCard', () => {
  it('AC-S183-1: HIGH riskLevel 顯示高風險與一綠三紅', () => {
    render(<SecurityHeroCard riskLevel="HIGH" active={false} onClick={vi.fn()} />)

    expect(screen.getByTestId('security-value').textContent).toBe('高風險')
    expect(screen.getByText('1 個綠燈 · 3 個紅燈')).toBeTruthy()
    expect([0, 1, 2, 3].map(lightState)).toEqual(['green', 'red', 'red', 'red'])
  })

  it('AC-S183-2: NONE riskLevel 顯示無風險與四個綠燈', () => {
    render(<SecurityHeroCard riskLevel="NONE" active={false} onClick={vi.fn()} />)

    expect(screen.getByTestId('security-value').textContent).toBe('無風險')
    expect(screen.getByText('4 個綠燈')).toBeTruthy()
    expect([0, 1, 2, 3].map(lightState)).toEqual(['green', 'green', 'green', 'green'])
  })

  it('AC-S183-3: LOW riskLevel 顯示低風險與三綠一紅', () => {
    render(<SecurityHeroCard riskLevel="LOW" active={false} onClick={vi.fn()} />)

    expect(screen.getByTestId('security-value').textContent).toBe('低風險')
    expect(screen.getByText('3 個綠燈 · 1 個紅燈')).toBeTruthy()
    expect([0, 1, 2, 3].map(lightState)).toEqual(['green', 'green', 'green', 'red'])
  })

  it('AC-S183-4: MEDIUM riskLevel 顯示中風險與二綠二紅', () => {
    render(<SecurityHeroCard riskLevel="MEDIUM" active={false} onClick={vi.fn()} />)

    expect(screen.getByTestId('security-value').textContent).toBe('中風險')
    expect(screen.getByText('2 個綠燈 · 2 個紅燈')).toBeTruthy()
    expect([0, 1, 2, 3].map(lightState)).toEqual(['green', 'green', 'red', 'red'])
  })

  it('click 觸發 onClick', () => {
    const onClick = vi.fn()
    render(<SecurityHeroCard riskLevel="NONE" active={false} onClick={onClick} />)

    fireEvent.click(screen.getByTestId('security-hero-card'))

    expect(onClick).toHaveBeenCalledOnce()
  })

  it('riskLevel=null → "—" + 未評估', () => {
    render(<SecurityHeroCard riskLevel={null} active={false} onClick={vi.fn()} />)

    expect(screen.getByTestId('security-value').textContent).toBe('—')
    expect(screen.getByText('未評估')).toBeTruthy()
    expect(screen.queryByTestId('risk-light-0')).toBeNull()
  })
})
