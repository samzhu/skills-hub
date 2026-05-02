import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BeamFrame } from './BeamFrame'

/**
 * BeamFrame tests — S097 thin wrapper around `border-beam` package。
 * 不測 visual beam animation（package internals）；只測 children pass-through。
 */

describe('BeamFrame — S097 wrapper', () => {
  it('AC-1: renders children', () => {
    render(
      <BeamFrame>
        <button>primary CTA</button>
      </BeamFrame>,
    )
    expect(screen.getByRole('button', { name: 'primary CTA' })).toBeInTheDocument()
  })

  it('AC-2: passes through complex children tree', () => {
    render(
      <BeamFrame>
        <div>
          <span>nested</span>
          <a href="/x">link</a>
        </div>
      </BeamFrame>,
    )
    expect(screen.getByText('nested')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'link' })).toHaveAttribute('href', '/x')
  })
})
