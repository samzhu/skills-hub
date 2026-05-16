import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SearchBar } from './SearchBar'

/**
 * SearchBar tests — controlled input + onChange callback。
 * Pure component test：value prop + onChange contract。
 */

describe('SearchBar', () => {
  const placeholder = '描述你想完成的任務或搜尋技能...'

  it('AC-1: renders input with current value', () => {
    render(<SearchBar value="docker" onChange={vi.fn()} />)
    const input = screen.getByPlaceholderText(placeholder) as HTMLInputElement
    expect(input).toBeInTheDocument()
    expect(input.value).toBe('docker')
  })

  it('AC-S178-12: placeholder matches semantic entry', () => {
    render(<SearchBar value="" onChange={vi.fn()} />)
    expect(screen.getByPlaceholderText(placeholder)).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('搜尋名稱、描述或分類...')).not.toBeInTheDocument()
  })

  it('AC-2: type=search enables browser clear button', () => {
    render(<SearchBar value="" onChange={vi.fn()} />)
    const input = screen.getByPlaceholderText(placeholder)
    expect(input.getAttribute('type')).toBe('search')
  })

  it('AC-3: typing triggers onChange with new value', () => {
    const onChange = vi.fn()
    render(<SearchBar value="" onChange={onChange} />)
    const input = screen.getByPlaceholderText(placeholder)
    fireEvent.change(input, { target: { value: 'compose' } })
    expect(onChange).toHaveBeenCalledWith('compose')
  })

  it('AC-4: clearing input passes empty string to onChange', () => {
    const onChange = vi.fn()
    render(<SearchBar value="docker" onChange={onChange} />)
    const input = screen.getByPlaceholderText(placeholder)
    fireEvent.change(input, { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith('')
  })
})
