import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SkillDetailPage } from './SkillDetailPage'

/**
 * SkillDetailPage error path tests — 對齊 docs/grimo/test-cases.md Round 1.4
 * negative case + S039 區分 404 not-found 與其他 server / network error。
 */

const renderPage = (id = 'non-existent-uuid') => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/skills/${id}`]}>
        <Routes>
          <Route path="/skills/:id" element={<SkillDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SkillDetailPage — error paths (ledger Round 1.4)', () => {
  it('AC-1: 404 not-found shows specific 找不到此技能 message', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({ error: 'NotFound', message: 'Skill not found' }),
    } as Response)
    renderPage('non-existent-uuid')
    await waitFor(() => {
      expect(screen.getByText('找不到此技能')).toBeInTheDocument()
    })
    // 404 不顯 retry hint（per S039 區分）
    expect(screen.queryByText('請稍後重試或重新整理頁面')).not.toBeInTheDocument()
  })

  it('AC-2: 500 server error shows generic error + retry hint', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: 'Internal', message: 'boom' }),
    } as Response)
    renderPage('any-id')
    await waitFor(() => {
      expect(screen.getByText('載入技能時發生錯誤')).toBeInTheDocument()
    })
    expect(screen.getByText('請稍後重試或重新整理頁面')).toBeInTheDocument()
  })

  it('AC-3 (S102): error state shows 返回列表 link to /browse', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({}),
    } as Response)
    renderPage('xxx')
    await waitFor(() => {
      const link = screen.getByText('返回列表')
      expect(link.closest('a')).toHaveAttribute('href', '/browse')
    })
  })
})
