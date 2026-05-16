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

describe('SemanticSearchPage — S178 browse search routing docs', () => {
  it('AC-S178-10: docs CTA opens browse as the semantic search entry', () => {
    renderPage()

    const cta = screen.getByRole('link', { name: '前往瀏覽頁試試語意搜尋 →' })

    expect(cta).toHaveAttribute('href', '/browse')
    expect(screen.queryByRole('link', { name: '試試語意搜尋 →' })).not.toBeInTheDocument()
  })
})
