import { useEffect, useState } from 'react'

/**
 * Returns a value only after it has stayed unchanged for the requested delay.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebounced(value)
    }, delayMs)
    return () => window.clearTimeout(timer)
  }, [delayMs, value])

  return debounced
}
