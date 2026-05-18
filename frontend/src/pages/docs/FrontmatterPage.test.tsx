import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FrontmatterPage } from './FrontmatterPage'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/docs/frontmatter']}>
        <FrontmatterPage />
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

describe('FrontmatterPage — S194 official format docs', () => {
  it('AC-S194-5: frontmatter docs describe official format and compatibility penalty', () => {
    renderPage()

    expect(screen.getByText('官方格式：空白分隔字串')).toBeInTheDocument()
    expect(screen.getByText('string key/value')).toBeInTheDocument()
    expect(screen.getByText(/compatibility warning/)).toBeInTheDocument()
    expect(screen.getByText('VALIDATION')).toBeInTheDocument()
  })
})
