import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuth } from '@/hooks/useAuth'
import designSource from '../../../docs/grimo/ui/DESIGN.md?raw'
import requestBoardPrototype from '../../../docs/grimo/ui/prototype/Skills Hub Request Board.html?raw'
import { RequestBoardPage } from './RequestBoardPage'

vi.mock('@/hooks/useAuth', () => ({
  useAuth: vi.fn(),
}))

const mockUseAuth = vi.mocked(useAuth)

/**
 * S096g2-T04 — RequestBoardPage AC-15 / AC-16 / AC-17 isolation tests。
 *
 * URL-aware fetch mock：
 * - GET /api/v1/requests → list (votes desc 預設)
 * - POST /api/v1/requests → 201 {id}
 * - POST /api/v1/requests/{id}/vote → {voted, voteCount}
 * - GET /api/v1/me → current user identity (LAB mode shape)
 * - GET /api/v1/notifications/unread-count → AppShell bell probe
 *
 * 取代 S103 disabled-button assertion（T04 ship 後 CTA 應啟用，舊 invariant
 * 隨 stub 退場）；S103 「no spec ID leak in user-facing strings」invariant
 * carry-forward 到 AC-15。
 */

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/requests']}>
        <Routes>
          <Route path="/requests" element={<RequestBoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const meResponse = {
  sub: 'alice',
  roles: ['user'],
  groups: [],
  companyId: null,
  deptId: null,
  scope: '',
}

const sampleRequests = [
  {
    id: 'r1',
    title: 'k8s autoscaler skill',
    description: '需要 k8s HPA 自動建議',
    requesterId: 'alice',
    voteCount: 38,
    createdAt: '2026-05-03T10:00:00Z',
    updatedAt: '2026-05-03T10:00:00Z',
  },
  {
    id: 'r2',
    title: 'terraform plan summarizer',
    description: '解析 plan 輸出',
    requesterId: 'bob',
    voteCount: 24,
    createdAt: '2026-05-02T10:00:00Z',
    updatedAt: '2026-05-02T10:00:00Z',
  },
  {
    id: 'r3',
    title: 'sql linter',
    description: 'PostgreSQL 風格檢查',
    requesterId: 'carol',
    voteCount: 19,
    createdAt: '2026-05-01T10:00:00Z',
    updatedAt: '2026-05-01T10:00:00Z',
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  mockUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      userId: 'u_alice',
      handle: 'alice',
      sub: 'alice',
      email: 'alice@example.com',
      name: 'Alice',
    },
    login: vi.fn(),
    logout: vi.fn(),
  })
})

describe('RequestBoardPage (S196)', () => {
  it('AC-S196-1: /requests 顯示瀏覽需求與我要開需求 tabs', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    const tablist = screen.getByRole('tablist', { name: '需求看板模式' })
    const browseTab = within(tablist).getByRole('tab', { name: /瀏覽需求/ })
    const createTab = within(tablist).getByRole('tab', { name: '我要開需求' })

    expect(browseTab).toHaveAttribute('aria-selected', 'true')
    expect(createTab).toHaveAttribute('aria-selected', 'false')
    expect(screen.queryByRole('tab', { name: '尚無勇者' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '接手中' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '已結案' })).not.toBeInTheDocument()

    await screen.findByRole('link', { name: 'k8s autoscaler skill' })

    // S103 invariant carry-forward: 無 spec ID leak in user-facing strings
    expect(screen.queryByText(/S096g\d/)).not.toBeInTheDocument()
  })

  it('AC-S196-2: 瀏覽需求顯示需求卡、投票按鈕、detail link 與排行', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    const browsePanel = await screen.findByRole('tabpanel', { name: /瀏覽需求/ })
    await waitFor(() => {
      expect(within(browsePanel).getByRole('link', { name: 'k8s autoscaler skill' })).toBeInTheDocument()
    })
    expect(within(browsePanel).getByRole('link', { name: 'terraform plan summarizer' })).toBeInTheDocument()
    expect(within(browsePanel).getByRole('link', { name: 'sql linter' })).toBeInTheDocument()
    expect(within(browsePanel).getAllByRole('button', { name: '投票' })).toHaveLength(3)

    const titleLink = within(browsePanel).getByRole('link', { name: 'k8s autoscaler skill' })
    expect(titleLink).toHaveAttribute('href', '/requests/r1')

    const ranking = within(browsePanel).getByRole('list', { name: '需求排行榜' })
    const rankingItems = within(ranking).getAllByRole('listitem')
    expect(rankingItems.map((item) => item.textContent)).toEqual([
      expect.stringContaining('k8s autoscaler skill38'),
      expect.stringContaining('terraform plan summarizer24'),
      expect.stringContaining('sql linter19'),
    ])
  })

  it('AC-S196-4: 排序只呼叫 sort=votes 或 sort=created 且不送 status', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    await screen.findByRole('link', { name: 'k8s autoscaler skill' })
    fireEvent.click(screen.getByRole('button', { name: '最新' }))

    await waitFor(() => {
      const requestCalls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
        .map((c) => String(c[0]))
        .filter((url) => url.includes('/api/v1/requests') && !url.includes('/vote'))
      expect(requestCalls.some((url) => url.includes('sort=votes'))).toBe(true)
      expect(requestCalls.some((url) => url.includes('sort=created'))).toBe(true)
      expect(requestCalls.every((url) => !url.includes('status='))).toBe(true)
    })
  })

  it('AC-S196-5: 空狀態 action 切到我要開需求 tab', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    await screen.findByText('目前還沒人發起需求。')
    fireEvent.click(screen.getByRole('button', { name: '我要開需求' }))

    expect(screen.getByRole('tab', { name: '我要開需求' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: '我要開需求' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '回去瀏覽現有技能' })).not.toBeInTheDocument()
  })

  it('S156c AC-17: 點 vote 按鈕 → POST toggle + count 更新為 server 值', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/vote') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ voted: true, voteCount: 39 }),
        } as Response)
      }
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([sampleRequests[0]]) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    await screen.findByRole('link', { name: 'k8s autoscaler skill' })

    const voteButton = screen.getByRole('button', { name: '投票' })
    expect(within(voteButton).getByText('38')).toBeInTheDocument()

    fireEvent.click(voteButton)

    await waitFor(() => {
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const postCall = calls.find((c) => c[1]?.method === 'POST' && c[0].includes('/vote'))
      expect(postCall).toBeDefined()
      expect(postCall![0]).toContain('/api/v1/requests/r1/vote')
    })

    await waitFor(() => {
      const votedButton = screen.getByRole('button', { name: '已投票，再點取消' })
      expect(within(votedButton).getByText('39')).toBeInTheDocument()
    })
  })

  it('AC-S196-3: 我要開需求 tab inline submit 送出 title 與 description', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/requests') && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ id: 'r-new' }) } as Response)
      }
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: '我要開需求' }))
    fireEvent.change(screen.getByLabelText('需求標題（最多 200 字）'), {
      target: { value: 'docker compose linter' },
    })
    fireEvent.change(screen.getByLabelText('需求說明（最多 2000 字）'), {
      target: { value: '檢查 compose.yaml 結構' },
    })
    fireEvent.click(screen.getByRole('button', { name: '送出需求' }))

    await waitFor(() => {
      const postCall = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls.find(
        (call) => String(call[0]).includes('/api/v1/requests') && call[1]?.method === 'POST',
      )
      expect(postCall).toBeDefined()
      expect(postCall![1]?.body).toBe(JSON.stringify({
        title: 'docker compose linter',
        description: '檢查 compose.yaml 結構',
      }))
    })

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /瀏覽需求/ })).toHaveAttribute('aria-selected', 'true')
    })
  })

  it('AC-S196-3: 未登入按送出需求不送 POST 並走 login', async () => {
    const login = vi.fn()
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login,
      logout: vi.fn(),
    })
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: '我要開需求' }))
    await waitFor(() => {
      expect(screen.getByLabelText('需求標題（最多 200 字）')).toBeEnabled()
    })
    fireEvent.change(screen.getByLabelText('需求標題（最多 200 字）'), {
      target: { value: 'docker compose linter' },
    })
    fireEvent.change(screen.getByLabelText('需求說明（最多 2000 字）'), {
      target: { value: '檢查 compose.yaml 結構' },
    })
    fireEvent.click(screen.getByRole('button', { name: '送出需求' }))

    const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(calls.some((call) => String(call[0]).includes('/api/v1/requests') && call[1]?.method === 'POST')).toBe(false)
    expect(login).toHaveBeenCalledTimes(1)
  })

  it('AC-S196-7: /requests design/prototype wording 不描述 status tab 或 claim/fulfill', () => {
    expect(designSource).toContain('two primary tabs')
    expect(designSource).toContain('inline create')
    expect(`${designSource}\n${requestBoardPrototype}`).not.toMatch(/尚無勇者|接手中|已結案|claim|fulfill/)
  })
})
