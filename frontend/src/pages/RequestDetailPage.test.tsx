import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RequestDetailPage } from './RequestDetailPage'

/**
 * S156c AC-9 / AC-11 — RequestDetailPage 渲染 + 互動 + 404 友善。
 *
 * URL-aware fetch mock 對齊 RequestBoardPage.test pattern：
 * - GET /api/v1/requests/{id} → RequestDetail (含 comments + canDelete)
 * - GET /api/v1/me → current user
 * - GET /api/v1/notifications/unread-count → AppShell bell probe
 */

const renderPage = (id: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/requests/${id}`]}>
        <Routes>
          <Route path="/requests/:id" element={<RequestDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const meResponse = {
  userId: 'u_alice',
  handle: 'alice',
  sub: 'alice',
  email: null,
  name: null,
  roles: ['user'],
  groups: [],
  companyId: null,
  deptId: null,
  scope: '',
  picture: null,
}

const sampleDetail = {
  id: 'r1',
  title: '需要 k8s autoscaler skill',
  description: '需要 k8s HPA 自動建議\n（多行測試）',
  requesterId: 'u_alice',
  requesterDisplayName: 'Alice Chen',
  requesterHandle: 'alice',
  voteCount: 12,
  createdAt: '2026-05-03T10:00:00Z',
  updatedAt: '2026-05-03T10:00:00Z',
  canDelete: true,
  comments: [
    {
      id: 'c1',
      authorId: 'u_bob',
      authorDisplayName: 'Bob Lin',
      authorHandle: null,
      content: '+1 我也需要',
      createdAt: '2026-05-03T11:00:00Z',
    },
    {
      id: 'c2',
      authorId: 'u_alice',
      authorDisplayName: 'Alice Chen',
      authorHandle: null,
      content: '附上更多 context',
      createdAt: '2026-05-03T12:00:00Z',
    },
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RequestDetailPage (S156c)', () => {
  it('AC-9: 渲染 title / description / vote / comments / form', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByText('需要 k8s autoscaler skill')).toBeInTheDocument()
    })
    // description rendered
    expect(screen.getByText(/需要 k8s HPA 自動建議/)).toBeInTheDocument()
    // vote count displayed
    expect(screen.getByText('12')).toBeInTheDocument()
    // comments (ASC by createdAt: c1 then c2)
    expect(screen.getByText('+1 我也需要')).toBeInTheDocument()
    expect(screen.getByText('附上更多 context')).toBeInTheDocument()
    // comment form textarea exists
    expect(screen.getByPlaceholderText('留下你的想法...')).toBeInTheDocument()
  })

  it('AC-9: canDelete=true → 顯示「刪除」按鈕', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByTestId('delete-request-btn')).toBeInTheDocument()
    })
  })

  it('AC-9: canDelete=false → 不顯示「刪除」按鈕', async () => {
    const nonOwnerDetail = { ...sampleDetail, canDelete: false }
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(nonOwnerDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByText('需要 k8s autoscaler skill')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('delete-request-btn')).not.toBeInTheDocument()
  })

  it('AC-9: own comment 顯 Delete button；他人 comment 不顯', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByText('附上更多 context')).toBeInTheDocument()
    })
    // me=u_alice；c1.authorId=u_bob → no delete；c2.authorId=u_alice → 1 delete button
    const deleteButtons = screen.getAllByRole('button', { name: '刪除留言' })
    expect(deleteButtons).toHaveLength(1)
  })

  it('AC-S192-6: comment row 顯示 authorDisplayName，刪除判斷仍使用 authorId', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => expect(screen.getByText('附上更多 context')).toBeInTheDocument())
    expect(screen.getByText('Bob Lin')).toBeInTheDocument()
    expect(screen.getByText('Alice Chen')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('u_bob')
    expect(document.body.textContent).not.toContain('u_alice')
    expect(screen.getAllByRole('button', { name: '刪除留言' })).toHaveLength(1)
  })

  it('AC-S200-3: detail header 顯示 requesterDisplayName，不顯 requesterId', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByText('Alice Chen · 2026/5/3')).toBeInTheDocument()
    })
    expect(document.body.textContent).not.toContain('u_alice')
  })

  it('AC-S200-5: display data 缺失時 UI 不 fallback 顯示 u_<id>', async () => {
    const detailWithoutDisplay = {
      ...sampleDetail,
      requesterId: 'u_missing',
      requesterDisplayName: null,
      requesterHandle: null,
      comments: [],
      canDelete: false,
    }
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(detailWithoutDisplay) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('r1')

    await waitFor(() => {
      expect(screen.getByText('2026/5/3')).toBeInTheDocument()
    })
    expect(screen.queryByText(/.+ · 2026\/5\/3/)).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('u_missing')
  })

  it('AC-9: 送出 comment → POST /comments + textarea 清空', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/requests/r1/comments') && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ id: 'c-new' }) } as Response)
      }
      if (url.includes('/api/v1/requests/r1')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleDetail) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })
    ;(globalThis as any).fetch = fetchMock

    renderPage('r1')

    const textarea = (await waitFor(() => screen.getByPlaceholderText('留下你的想法...'))) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: '新的留言' } })
    fireEvent.click(screen.getByRole('button', { name: '送出' }))

    await waitFor(() => {
      const postCall = fetchMock.mock.calls.find((c) => c[1]?.method === 'POST' && c[0].includes('/comments'))
      expect(postCall).toBeDefined()
      expect(postCall![1].body).toContain('"content":"新的留言"')
    })
    // 送出後 textarea 清空
    await waitFor(() => expect(textarea.value).toBe(''))
  })

  it('AC-11: 404 → 顯「找不到此需求」+ 回看板 link', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests/nonexistent')) {
        return Promise.resolve({
          ok: false,
          status: 404,
          json: () => Promise.resolve({ error: 'REQUEST_NOT_FOUND', message: 'not found' }),
        } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage('nonexistent')

    await waitFor(() => {
      expect(screen.getByText('找不到此需求。')).toBeInTheDocument()
    })
    // EmptyState 的 primaryAction 為 anchor
    const backLink = screen.getByText('回需求看板')
    expect(backLink).toBeInTheDocument()
  })
})
