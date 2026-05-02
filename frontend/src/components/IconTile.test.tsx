import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { IconTile } from './IconTile'

/**
 * IconTile tests — initial derivation + size class + accessibility invariants
 * S085 component contract。
 *
 * 不測 inline-style hex (per ALWAYS rule)；只測 derived initial logic +
 * size class + aria attribute。
 */

describe('IconTile — S085', () => {
  it('AC-1: single-word name takes first letter uppercase', () => {
    const { container } = render(<IconTile name="docker" category="devops" />)
    expect(container.textContent).toBe('D')
  })

  it('AC-2: hyphenated name takes first letter of first 2 words', () => {
    const { container } = render(<IconTile name="docker-compose" category="devops" />)
    expect(container.textContent).toBe('DC')
  })

  it('AC-3: underscore-separated also splits', () => {
    const { container } = render(<IconTile name="auth_helper" category="security" />)
    expect(container.textContent).toBe('AH')
  })

  it('AC-4: empty / whitespace name falls back to ?', () => {
    const { container } = render(<IconTile name="   " />)
    expect(container.textContent).toBe('?')
  })

  it('AC-5: size prop maps to correct width class', () => {
    const sm = render(<IconTile name="x" size="sm" />)
    expect(sm.container.querySelector('span')?.className).toContain('w-6')
    const md = render(<IconTile name="x" size="md" />)
    expect(md.container.querySelector('span')?.className).toContain('w-[30px]')
    const lg = render(<IconTile name="x" size="lg" />)
    expect(lg.container.querySelector('span')?.className).toContain('w-10')
    const xl = render(<IconTile name="x" size="xl" />)
    expect(xl.container.querySelector('span')?.className).toContain('w-[52px]')
  })

  it('AC-6: aria-hidden=true (decorative)', () => {
    const { container } = render(<IconTile name="x" />)
    expect(container.querySelector('span')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('AC-7: unknown category falls back gracefully (no crash, default tile)', () => {
    expect(() => render(<IconTile name="x" category="ufo-gibberish" />)).not.toThrow()
  })
})
