import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { OverviewPage } from './OverviewPage'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <OverviewPage />
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

describe('OverviewPage — S172 docs responsive cards', () => {
  it('AC-S172-7: docs feature cards use two columns before xl', () => {
    renderPage()

    const featureGrid = screen.getByTestId('docs-overview-feature-grid')
    expect(featureGrid.className).toContain('sm:grid-cols-2')
    expect(featureGrid.className).toContain('xl:grid-cols-3')
    expect(featureGrid.className).not.toContain('md:grid-cols-3')
  })
})
