import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
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

  it('S098e3 AC-9: 不論 0 或 N flags 都顯「回報問題」CTA', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    } as Response)
    renderFlagsList()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '回報問題' })).toBeInTheDocument()
    })
  })

  it('S098e3 AC-10: 點 CTA 開 modal → 選 type + 填 desc + Submit 觸發 POST', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      // AuthGatedButton 需要 useAuth authenticated，mock /me 回已登入 user
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ sub: 'test-user' }) } as Response)
      }
      if (url.includes('/flags') && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ id: 'new-flag-id' }) } as Response)
      }
      // GET flags → 空 list
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
    })
    renderFlagsList()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '回報問題' })).toBeInTheDocument()
    })
    // AuthGatedButton 需 useAuth=authenticated 才觸發 onClick；waitFor 重試直到 /me 解析完
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: '回報問題' }))
      expect(screen.getByRole('dialog', { name: '回報問題' })).toBeInTheDocument()
    })

    // 選 type=spam radio
    const spamRadio = screen.getByRole('radio', { name: '垃圾內容' })
    fireEvent.click(spamRadio)
    // 填 description
    fireEvent.change(screen.getByLabelText(/說明/), { target: { value: '重複內容' } })
    // Submit
    fireEvent.click(screen.getByRole('button', { name: '送出' }))

    await waitFor(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const postCall = calls.find((c) => c[1]?.method === 'POST')
      expect(postCall).toBeDefined()
      expect(postCall![1].body).toContain('"type":"spam"')
      expect(postCall![1].body).toContain('重複內容')
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
