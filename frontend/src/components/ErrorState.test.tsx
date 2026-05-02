import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorState } from './ErrorState'

/**
 * ErrorState tests — variant rendering + optional message + custom icon。
 */

describe('ErrorState — S100d', () => {
  it('AC-1: inline variant renders title only', () => {
    render(<ErrorState title="發佈失敗" />)
    expect(screen.getByText('發佈失敗')).toBeInTheDocument()
  })

  it('AC-2: inline variant with message renders both', () => {
    render(<ErrorState title="發佈失敗" message="zip 過大" />)
    expect(screen.getByText('發佈失敗')).toBeInTheDocument()
    expect(screen.getByText('zip 過大')).toBeInTheDocument()
  })

  it('AC-3: centered variant renders both', () => {
    render(<ErrorState variant="centered" title="載入失敗" message="請重新整理" />)
    expect(screen.getByText('載入失敗')).toBeInTheDocument()
    expect(screen.getByText('請重新整理')).toBeInTheDocument()
  })

  it('AC-4: custom icon override replaces default', () => {
    const { container } = render(
      <ErrorState
        title="x"
        icon={<svg data-testid="custom-icon" />}
      />,
    )
    expect(container.querySelector('[data-testid="custom-icon"]')).toBeInTheDocument()
  })

  it('AC-5: ReactNode message renders inline children', () => {
    render(
      <ErrorState
        title="error"
        message={<span>nested <code>code</code> content</span>}
      />,
    )
    expect(screen.getByText('code')).toBeInTheDocument()
  })

  it('AC-6: extra className appends to outer container', () => {
    const { container } = render(<ErrorState title="x" className="mt-12" />)
    expect(container.firstChild).toHaveProperty('className', expect.stringContaining('mt-12'))
  })
})
