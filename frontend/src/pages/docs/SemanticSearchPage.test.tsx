import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SemanticSearchPage } from './SemanticSearchPage'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/docs/semantic-search']}>
        <SemanticSearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: false,
    status: 401,
    json: () => Promise.resolve({ error: 'unauthorized' }),
  } as Response)
})

describe('SemanticSearchPage — S189 browse search routing docs', () => {
  it('AC-S189-7: docs CTA opens browse as the semantic search entry', () => {
    renderPage()

    const cta = screen.getByRole('link', { name: '前往瀏覽頁試試語意搜尋 →' })

    expect(cta).toHaveAttribute('href', '/browse')
    expect(screen.queryByRole('link', { name: '試試語意搜尋 →' })).not.toBeInTheDocument()
  })

  it('AC-S203-7: semantic docs describe paged results not top-k limit', () => {
    renderPage()

    expect(screen.getByText(/第 1 頁回 page=0/)).toBeInTheDocument()
    expect(screen.getByText(/下一頁回 page=1/)).toBeInTheDocument()
    expect(screen.getByText(/每批預設 size=10/)).toBeInTheDocument()
    expect(screen.queryByText(/top-k/)).not.toBeInTheDocument()
    expect(screen.queryByText(/default k=20/)).not.toBeInTheDocument()
  })
})
