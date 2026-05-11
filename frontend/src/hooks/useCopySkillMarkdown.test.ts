import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useCopySkillMarkdown } from './useCopySkillMarkdown'

const FIXTURE = '# Test Skill\nSKILL.md content here.'
const SKILL_ID = 'abc-123'

const mockWrite = vi.fn().mockResolvedValue(undefined)

beforeEach(() => {
  vi.clearAllMocks()

  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    text: () => Promise.resolve(FIXTURE),
  } as Response)

  // jsdom doesn't natively support ClipboardItem; provide a constructable stub
  class MockClipboardItem {
    _items: Record<string, Promise<Blob>>
    constructor(items: Record<string, Promise<Blob>>) {
      this._items = items
    }
  }
  globalThis.ClipboardItem = MockClipboardItem as unknown as typeof ClipboardItem

  Object.defineProperty(navigator, 'clipboard', {
    value: { write: mockWrite },
    writable: true,
    configurable: true,
  })
})

describe('useCopySkillMarkdown', () => {
  it('AC-7: copy() calls clipboard.write with ClipboardItem containing SKILL.md bytes (cache miss)', async () => {
    const { result } = renderHook(() => useCopySkillMarkdown(SKILL_ID))

    await act(async () => {
      await result.current.copy()
    })

    expect(mockWrite).toHaveBeenCalledOnce()
    // The write call receives an array with one ClipboardItem
    const [items] = mockWrite.mock.calls[0] as [{ _items: Record<string, Promise<Blob>> }[]]
    expect(items).toHaveLength(1)
    const blobPromise = items[0]._items['text/plain']
    expect(blobPromise).toBeInstanceOf(Promise)
    const blob = await blobPromise
    expect(await blob.text()).toBe(FIXTURE)
  })

  it('AC-7: copy() uses cached content on second call (cache hit — no second fetch)', async () => {
    const { result } = renderHook(() => useCopySkillMarkdown(SKILL_ID))

    await act(async () => { await result.current.copy() })
    vi.clearAllMocks()
    // Second copy should NOT call fetch again
    await act(async () => { await result.current.copy() })

    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(mockWrite).toHaveBeenCalledOnce()
  })

  it('prefetch() populates cache silently and does not throw on fetch error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: false, status: 403 } as Response)
    const { result } = renderHook(() => useCopySkillMarkdown(SKILL_ID))

    await act(async () => { result.current.prefetch() })
    // no throw — prefetch fails silently
  })
})
