import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDebouncedValue } from './useDebouncedValue'

describe('useDebouncedValue', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('AC-S178-3: debounce publishes only the final query', () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: '' } },
    )

    expect(result.current).toBe('')
    rerender({ value: 'd' })
    expect(result.current).toBe('')

    act(() => vi.advanceTimersByTime(100))
    rerender({ value: 'dd' })
    act(() => vi.advanceTimersByTime(299))
    expect(result.current).toBe('')

    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toBe('dd')
  })
})
