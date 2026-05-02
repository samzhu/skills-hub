import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SearchBar } from './SearchBar'

/**
 * SearchBar tests — controlled input + onChange callback。
 * Pure component test：value prop + onChange contract。
 */

describe('SearchBar', () => {
  it('AC-1: renders input with current value', () => {
    render(<SearchBar value="docker" onChange={vi.fn()} />)
    const input = screen.getByPlaceholderText('搜尋名稱、描述或分類...') as HTMLInputElement
    expect(input).toBeInTheDocument()
    expect(input.value).toBe('docker')
  })

  it('AC-2: type=search enables browser clear button', () => {
    render(<SearchBar value="" onChange={vi.fn()} />)
    const input = screen.getByPlaceholderText('搜尋名稱、描述或分類...')
    expect(input.getAttribute('type')).toBe('search')
  })

  it('AC-3: typing triggers onChange with new value', () => {
    const onChange = vi.fn()
    render(<SearchBar value="" onChange={onChange} />)
    const input = screen.getByPlaceholderText('搜尋名稱、描述或分類...')
    fireEvent.change(input, { target: { value: 'compose' } })
    expect(onChange).toHaveBeenCalledWith('compose')
  })

  it('AC-4: clearing input passes empty string to onChange', () => {
    const onChange = vi.fn()
    render(<SearchBar value="docker" onChange={onChange} />)
    const input = screen.getByPlaceholderText('搜尋名稱、描述或分類...')
    fireEvent.change(input, { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith('')
  })
})
