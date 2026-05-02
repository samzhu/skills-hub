import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { NotificationsPage } from './NotificationsPage'

/**
 * NotificationsPage tests — 對齊 docs/grimo/test-cases.md Round 7.1。
 * Stub state：backend 回 [] → EmptyState clear tone with 3 stats placeholders。
 */

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

beforeEach(() => {
  vi.clearAllMocks()
  // Default: empty notifications + zero unread (AppShell bell badge)
  global.fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('unread-count')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ count: 0 }),
      } as Response)
    }
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    } as Response)
  })
})

describe('NotificationsPage — ledger Round 7.1', () => {
  it('AC-1: empty notifications renders EmptyState clear tone with 3 stats', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('都看完了，沒有未讀通知。')).toBeInTheDocument()
    })
    // Clear tone shows 3 stats per S094c contract
    expect(screen.getByText('本週新通知')).toBeInTheDocument()
    expect(screen.getByText('未讀')).toBeInTheDocument()
    expect(screen.getByText('上次接收')).toBeInTheDocument()
  })

  it('AC-2: page header renders 通知中心 + intro', async () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: '通知中心' })).toBeInTheDocument()
    expect(screen.getByText(/訂閱的 skill 有新版本/)).toBeInTheDocument()
  })

  it('AC-3: non-empty list renders rows instead of EmptyState', async () => {
    global.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('unread-count')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 1 }) } as Response)
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve([
          {
            id: 'n1',
            category: 'versions',
            title: '新版本發佈',
            body: 'date-formatter 1.2.0 已發佈',
            read: false,
            createdAt: '2026-04-30T00:00:00Z',
          },
        ]),
      } as Response)
    })
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('新版本發佈')).toBeInTheDocument()
    })
    // EmptyState 不該 render
    expect(screen.queryByText('都看完了，沒有未讀通知。')).not.toBeInTheDocument()
  })
})
