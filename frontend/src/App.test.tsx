import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route, Navigate } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { NotFoundPage } from './pages/NotFoundPage'

/**
 * 此測試只驗 NotFoundPage 本身渲染合約，不 mount 整個 <App />。
 *
 * S096h1: NotFoundPage 透過 AppShell 渲染；AppShell bell badge 用 useQuery
 * 查 unread count，需 QueryClientProvider context. 每 test 用 fresh QueryClient
 * 隔離 cache.
 */
describe('AC-1: NotFoundPage', () => {
  it('render 「404」與回首頁連結', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/bogus']}>
          <Routes>
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByText('404')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '回到首頁' })).toHaveAttribute('href', '/')
  })
})

/**
 * S143: /docs canonical entry redirect 行為。
 *
 * 用 sentinel 元素隔離測試 routing 邏輯 — 不拉入 OverviewPage / DocsLayout
 * 整條 dep chain（會觸發 AppShell + useAuth + bell unread query）。
 * 改 App.tsx redirect 時，這裡的 routes 設定也要同步維護。
 */
function renderDocsRoutes(initialPath: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/docs" element={<Navigate to="/docs/overview" replace />} />
          <Route path="/docs/overview" element={<div data-testid="overview-sentinel">OVERVIEW</div>} />
          <Route
            path="/docs/your-first-skill"
            element={<div data-testid="first-skill-sentinel">FIRST_SKILL</div>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('S143 AC-1: /docs redirect 至 /docs/overview', () => {
  it('當訪問 /docs 時，最終渲染 overview 頁', () => {
    renderDocsRoutes('/docs')
    expect(screen.getByTestId('overview-sentinel')).toBeInTheDocument()
    expect(screen.queryByTestId('first-skill-sentinel')).not.toBeInTheDocument()
  })
})

describe('S143 AC-3: docs 子頁不被 redirect', () => {
  it('當直接訪問 /docs/your-first-skill 時，渲染 walkthrough 頁不跳轉', () => {
    renderDocsRoutes('/docs/your-first-skill')
    expect(screen.getByTestId('first-skill-sentinel')).toBeInTheDocument()
    expect(screen.queryByTestId('overview-sentinel')).not.toBeInTheDocument()
  })

  it('當直接訪問 /docs/overview 時，正常渲染不重定向', () => {
    renderDocsRoutes('/docs/overview')
    expect(screen.getByTestId('overview-sentinel')).toBeInTheDocument()
  })
})
