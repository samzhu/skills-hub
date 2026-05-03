import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { NotificationsPage } from './NotificationsPage'

/**
 * S096h2-T04 — NotificationsPage tests covering AC-12（list + filter + mark-read + delete）
 * + AC-14（preferences modal toggle save）。
 *
 * URL-aware fetch mock 對齊 既有 frontend test pattern：
 * - GET /notifications → wrapper {items, hasNext}
 * - GET /notifications/unread-count → {count}
 * - GET /notifications/preferences → {flags, reviews, requests, versions}
 * - POST /notifications/{id}/read / read-all / preferences → ok
 * - DELETE /notifications/{id} → ok
 */

const sampleNotifs = [
  {
    id: 'n1', category: 'flags', title: '你的技能 X 被標記回報（spam）',
    body: '重複內容', skillId: 'sk-1', refEventId: 'sk-1:spam',
    readAt: null, createdAt: '2026-04-30T10:00:00Z',
  },
  {
    id: 'n2', category: 'flags', title: '你的技能 Y 被標記回報（malicious）',
    body: null, skillId: 'sk-2', refEventId: 'sk-2:malicious',
    readAt: null, createdAt: '2026-04-30T09:30:00Z',
  },
  {
    id: 'n3', category: 'flags', title: '你的技能 Z 被標記回報（broken）',
    body: null, skillId: 'sk-3', refEventId: 'sk-3:broken',
    readAt: null, createdAt: '2026-04-30T09:00:00Z',
  },
  {
    id: 'n4', category: 'reviews', title: 'bob 對你的技能 X 寫了 5★ 評論',
    body: null, skillId: 'sk-1', refEventId: 'rev-1',
    readAt: null, createdAt: '2026-04-30T08:00:00Z',
  },
  {
    id: 'n5', category: 'reviews', title: 'carol 對你的技能 X 寫了 4★ 評論',
    body: null, skillId: 'sk-1', refEventId: 'rev-2',
    readAt: null, createdAt: '2026-04-30T07:00:00Z',
  },
]

interface FetchMockState {
  notifs: typeof sampleNotifs
  preferences: { flags: boolean; reviews: boolean; requests: boolean; versions: boolean }
  postedReadIds: string[]
  postedReadAll: number
  postedDeleteIds: string[]
  postedPreferences: Array<Partial<typeof state.preferences>>
}

let state: FetchMockState

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const u = String(url)
  const method = init?.method ?? 'GET'

  if (u.includes('/notifications/unread-count')) {
    const count = state.notifs.filter((n) => n.readAt === null).length
    return { ok: true, status: 200, json: async () => ({ count }) } as Response
  }

  if (u.includes('/notifications/preferences')) {
    if (method === 'POST') {
      const body = JSON.parse(String(init?.body ?? '{}'))
      state.postedPreferences.push(body)
      state.preferences = { ...state.preferences, ...body }
      return { ok: true, status: 200, json: async () => state.preferences } as Response
    }
    return { ok: true, status: 200, json: async () => state.preferences } as Response
  }

  if (u.includes('/notifications/read-all')) {
    state.postedReadAll++
    state.notifs = state.notifs.map((n) => ({ ...n, readAt: '2026-04-30T11:00:00Z' }))
    return { ok: true, status: 204, json: async () => null } as Response
  }

  // POST /notifications/{id}/read
  const readMatch = u.match(/\/notifications\/([^/?]+)\/read$/)
  if (readMatch && method === 'POST') {
    const id = readMatch[1]
    state.postedReadIds.push(id)
    state.notifs = state.notifs.map((n) => (n.id === id ? { ...n, readAt: '2026-04-30T11:00:00Z' } : n))
    return { ok: true, status: 204, json: async () => null } as Response
  }

  // DELETE /notifications/{id}
  const delMatch = u.match(/\/notifications\/([^/?]+)$/)
  if (delMatch && method === 'DELETE') {
    const id = delMatch[1]
    state.postedDeleteIds.push(id)
    state.notifs = state.notifs.filter((n) => n.id !== id)
    return { ok: true, status: 204, json: async () => null } as Response
  }

  // GET /notifications?... → wrapper
  if (u.includes('/notifications')) {
    const params = new URLSearchParams(u.split('?')[1] ?? '')
    const cat = params.get('category')
    const items = cat ? state.notifs.filter((n) => n.category === cat) : state.notifs
    return { ok: true, status: 200, json: async () => ({ items, hasNext: false }) } as Response
  }

  return { ok: true, status: 200, json: async () => ({}) } as Response
})

beforeEach(() => {
  state = {
    notifs: structuredClone(sampleNotifs),
    preferences: { flags: true, reviews: true, requests: true, versions: true },
    postedReadIds: [],
    postedReadAll: 0,
    postedDeleteIds: [],
    postedPreferences: [],
  }
  fetchMock.mockClear()
  global.fetch = fetchMock as unknown as typeof fetch
})

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/notifications']}>
        <Routes>
          <Route path="/notifications" element={<NotificationsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NotificationsPage — AC-12 list + filter + mark-read + delete', () => {
  it('AC-12: 5 unread → renders 5 rows + filter chips + 全部已讀 button', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('你的技能 X 被標記回報（spam）')).toBeInTheDocument()
    })
    expect(screen.getByText('你的技能 Y 被標記回報（malicious）')).toBeInTheDocument()
    expect(screen.getByText('bob 對你的技能 X 寫了 5★ 評論')).toBeInTheDocument()
    // EmptyState 不應 render
    expect(screen.queryByText('都看完了，沒有未讀通知。')).not.toBeInTheDocument()
    // Filter chips 全部存在
    expect(screen.getByRole('button', { name: '全部' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '回報' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '評論' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '需求' })).toBeInTheDocument()
    // Hero buttons
    expect(screen.getByRole('button', { name: '全部已讀' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '設定' })).toBeInTheDocument()
  })

  it('AC-12: 點 row → POST /{id}/read；該筆 visually mark read', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    fireEvent.click(screen.getByText('你的技能 X 被標記回報（spam）'))

    await waitFor(() => {
      expect(state.postedReadIds).toEqual(['n1'])
    })
  })

  it('AC-12: 點「全部已讀」→ POST /read-all', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    fireEvent.click(screen.getByRole('button', { name: '全部已讀' }))

    await waitFor(() => {
      expect(state.postedReadAll).toBe(1)
    })
  })

  it('AC-12: 點 ✕ → DELETE /{id}；row 從 list 消失', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    // n1 row 的 ✕ button — 找包含 n1 title 的 row 內的 delete button
    const deleteButtons = screen.getAllByRole('button', { name: '刪除通知' })
    expect(deleteButtons.length).toBe(5)
    fireEvent.click(deleteButtons[0]) // 第一筆 = n1 (newest createdAt)

    await waitFor(() => {
      expect(state.postedDeleteIds).toContain('n1')
    })
  })

  it('AC-12: 點「評論」chip → list 只顯 reviews category', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    fireEvent.click(screen.getByRole('button', { name: '評論' }))

    await waitFor(() => {
      expect(screen.getByText('bob 對你的技能 X 寫了 5★ 評論')).toBeInTheDocument()
    })
    // flags 那 3 筆不應 render
    expect(screen.queryByText('你的技能 X 被標記回報（spam）')).not.toBeInTheDocument()
    // fetch 帶 category=reviews（apiFetch GET 不傳 init，第 2 arg 為 undefined → 用 calls 而非 toHaveBeenCalledWith）
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls.some((u) => u.includes('category=reviews'))).toBe(true)
  })
})

describe('NotificationsPage — AC-14 preferences modal', () => {
  it('AC-14: 點「設定」→ modal 開 + 4 toggle 顯示 + 全 enabled checkbox 預設打勾', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    fireEvent.click(screen.getByRole('button', { name: '設定' }))

    // 等 preferences fetch resolve 後 draft 入 state，toggle 才 render（之前是「載入設定中」）
    const flagsToggle = await screen.findByLabelText('回報')
    const dialog = screen.getByRole('dialog', { name: '通知訂閱偏好' })
    expect(flagsToggle).toBeChecked()
    expect(within(dialog).getByLabelText('評論')).toBeChecked()
    expect(within(dialog).getByLabelText('需求')).toBeChecked()
    expect(within(dialog).getByLabelText('新版本')).toBeDisabled()
  })

  it('AC-14: toggle flags off → 儲存 → POST partial body {flags:false}', async () => {
    renderPage()
    await waitFor(() => screen.getByText('你的技能 X 被標記回報（spam）'))

    fireEvent.click(screen.getByRole('button', { name: '設定' }))
    const flagsToggle = await screen.findByLabelText('回報')
    fireEvent.click(flagsToggle)
    expect(flagsToggle).not.toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: '儲存' }))

    await waitFor(() => {
      expect(state.postedPreferences).toContainEqual({ flags: false })
    })
    // server 回新 preferences；modal 自動 close
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '通知訂閱偏好' })).not.toBeInTheDocument()
    })
  })
})
