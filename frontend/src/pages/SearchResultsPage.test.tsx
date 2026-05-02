import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SearchResultsPage } from './SearchResultsPage'

// S102 — verify 清空 query 跟 EmptyState CTA 都正確 nav 到 /browse 而非 /
//   AC-3 = empty form submit → /browse
//   AC-4 = no q query string → EmptyState「瀏覽全部技能」href = /browse

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

const renderAt = (initialPath: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/search" element={<SearchResultsPage />} />
          <Route path="/browse" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SearchResultsPage — S102 routing residual fix', () => {
  it('AC-4: no q query → EmptyState 「瀏覽全部技能」 link href = /browse', async () => {
    renderAt('/search')
    const cta = await screen.findByRole('link', { name: '瀏覽全部技能' })
    expect(cta).toHaveAttribute('href', '/browse')
  })

  it('AC-3: 清空 search query 並提交 → navigate to /browse', async () => {
    renderAt('/search?q=foo')
    const input = screen.getByPlaceholderText(/搜尋/) as HTMLInputElement
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.submit(input.closest('form')!)
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/browse')
    })
  })
})
