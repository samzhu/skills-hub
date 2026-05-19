import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RestApiPage } from './RestApiPage'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/docs/rest-api']}>
        <RestApiPage />
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

describe('RestApiPage — S203 semantic paging docs', () => {
  it('AC-S203-7: REST docs list semantic q page size', () => {
    renderPage()

    expect(screen.getByText('/api/v1/search/semantic')).toBeInTheDocument()
    expect(screen.getByText('語意搜尋；query: q / page / size；response: Spring Slice content')).toBeInTheDocument()
    expect(screen.queryByText(/q \/ k/)).not.toBeInTheDocument()
    expect(screen.queryByText(/default 20/)).not.toBeInTheDocument()
    expect(screen.queryByText(/limit/)).not.toBeInTheDocument()
  })
})
