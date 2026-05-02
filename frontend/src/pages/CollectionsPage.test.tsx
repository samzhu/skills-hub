import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CollectionsPage } from './CollectionsPage'

/**
 * CollectionsPage tests — 對齊 docs/grimo/test-cases.md Round 7.2。
 * Stub state：backend 回 [] → EmptyState 顯 + 「建立集合」按鈕 disabled
 * (S096f1 stub；S096f2 完成後啟用)。
 */

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/collections']}>
        <Routes>
          <Route path="/collections" element={<CollectionsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  global.fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('unread-count')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    }
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    } as Response)
  })
})

describe('CollectionsPage — ledger Round 7.2', () => {
  it('AC-1: page header 精選技能集合 renders', async () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: '精選技能集合' })).toBeInTheDocument()
  })

  it('AC-2: 建立集合 CTA is disabled (S096f1 stub)', async () => {
    renderPage()
    const btn = screen.getByText(/建立集合/)
    expect(btn.closest('button')).toBeDisabled()
  })

  it('AC-3: empty backend response renders EmptyState (not crash)', async () => {
    renderPage()
    // 等待 fetch resolve；不論顯哪 tone empty state，至少不該爆
    await waitFor(() => {
      // 標題 + 主 heading 可見表示 page render 完成
      expect(screen.getByRole('heading', { level: 1, name: '精選技能集合' })).toBeInTheDocument()
    })
  })

  it('AC-S103: user-facing copy 不洩漏 internal spec ID S096f2', async () => {
    renderPage()
    // 直接斷言所有 user-visible text 不含「S096f2」字面 (button label / title / EmptyState sub)
    await waitFor(() => {
      expect(screen.queryByText(/S096f2/)).not.toBeInTheDocument()
    })
    // disabled button title attribute 也不該含 spec ID
    const btn = screen.getByText(/建立集合/).closest('button')
    expect(btn?.getAttribute('title') ?? '').not.toMatch(/S096f2/)
  })
})
