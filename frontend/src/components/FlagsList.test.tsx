import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FlagsList } from './FlagsList'

/**
 * S112-T03: FlagsList component — AC-1 / AC-2 isolation tests。
 *
 * 直接 render component 而非透過 SkillDetailPage Tabs（Radix Tabs JSDOM
 * fireEvent.click 不可靠）— component 已是 single responsibility，
 * unit test 對 hook 結果與 DOM 結構的驗證更直接。
 */

const SKILL_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const renderFlagsList = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <FlagsList skillId={SKILL_ID} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('FlagsList (S112-T03)', () => {
  it('S112 AC-1: 0 flags 顯示 EmptyState', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    } as Response)
    renderFlagsList()
    await waitFor(() => {
      expect(screen.getByText('目前沒有任何回報')).toBeInTheDocument()
    })
  })

  it('S112 AC-2: >0 flags 渲染 list with type/status pill', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([
        {
          id: 'f1',
          skillId: SKILL_ID,
          type: 'malicious',
          description: '此技能 SKILL.md 含 rm -rf 指令',
          reportedBy: 'anonymous',
          createdAt: '2026-05-03T03:00:00Z',
          status: 'OPEN',
        },
        {
          id: 'f2',
          skillId: SKILL_ID,
          type: 'spam',
          description: null,
          reportedBy: 'anonymous',
          createdAt: '2026-05-02T12:00:00Z',
          status: 'OPEN',
        },
      ]),
    } as Response)
    renderFlagsList()
    await waitFor(() => {
      expect(screen.getByText('惡意指令')).toBeInTheDocument()
    })
    expect(screen.getByText('垃圾內容')).toBeInTheDocument()
    expect(screen.getAllByText('待處理')).toHaveLength(2)
    expect(screen.getByText('此技能 SKILL.md 含 rm -rf 指令')).toBeInTheDocument()
    expect(screen.queryByText('目前沒有任何回報')).not.toBeInTheDocument()
  })
})
