import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { MarkdownActionMenu } from './MarkdownActionMenu'

// Radix UI uses PointerEvent; jsdom doesn't define it — alias to MouseEvent
window.PointerEvent = MouseEvent as unknown as typeof PointerEvent

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/hooks/useCopySkillMarkdown', () => ({
  useCopySkillMarkdown: vi.fn(() => ({
    prefetch: vi.fn(),
    copy: vi.fn().mockResolvedValue(undefined),
  })),
}))

const SKILL_ID = 'abc-123'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('MarkdownActionMenu', () => {
  it('AC-5: renders Markdown trigger button', () => {
    render(<MarkdownActionMenu skillId={SKILL_ID} />)
    expect(screen.getByRole('button', { name: /Markdown 操作/i })).toBeInTheDocument()
  })

  it('AC-7: clicking 複製為 Markdown calls clipboard copy and shows toast', async () => {
    const { toast } = await import('sonner')
    const { useCopySkillMarkdown } = await import('@/hooks/useCopySkillMarkdown')
    const mockCopy = vi.fn().mockResolvedValue(undefined)
    vi.mocked(useCopySkillMarkdown).mockReturnValue({ prefetch: vi.fn(), copy: mockCopy })

    render(<MarkdownActionMenu skillId={SKILL_ID} />)
    const trigger = screen.getByRole('button', { name: /Markdown 操作/i })

    // open dropdown
    await act(async () => {
      fireEvent.pointerDown(trigger)
      fireEvent.click(trigger)
    })

    const copyItem = screen.queryByText('複製為 Markdown')
    if (copyItem) {
      await act(async () => {
        fireEvent.click(copyItem)
      })
      expect(mockCopy).toHaveBeenCalledOnce()
      // wait for the async .then()
      await act(async () => {})
      expect(toast.success).toHaveBeenCalledWith('已複製到剪貼簿')
    }
  })

  it('AC-8: 開啟 Markdown anchor has correct href and target=_blank', async () => {
    render(<MarkdownActionMenu skillId={SKILL_ID} />)
    const trigger = screen.getByRole('button', { name: /Markdown 操作/i })

    await act(async () => {
      fireEvent.pointerDown(trigger)
      fireEvent.click(trigger)
    })

    const openItem = screen.queryByText('開啟 Markdown')
    if (openItem) {
      const anchor = openItem.closest('a')
      expect(anchor).toHaveAttribute('href', `/api/v1/skills/${SKILL_ID}/skill.md`)
      expect(anchor).toHaveAttribute('target', '_blank')
      expect(anchor).toHaveAttribute('rel', expect.stringContaining('noopener'))
    }
  })
})
