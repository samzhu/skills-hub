import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
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
