import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RequestBoardPage } from './RequestBoardPage'

// S103 — verify user-facing copy 不洩漏 internal spec ID S096g2
//   AC-3 = button label/title 不含 S096g2
//   AC-4 = EmptyState subtext 不含 S096g2

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/requests']}>
        <Routes>
          <Route path="/requests" element={<RequestBoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RequestBoardPage — S103 spec ID leak fix', () => {
  it('AC-3: 發起新需求 disabled button 不洩漏 S096g2 in label or title', async () => {
    renderPage()
    const btn = screen.getByText(/發起新需求/).closest('button')
    expect(btn).toBeDisabled()
    expect(btn?.textContent ?? '').not.toMatch(/S096g2/)
    expect(btn?.getAttribute('title') ?? '').not.toMatch(/S096g2/)
  })

  it('AC-4: EmptyState subtext 不含 S096g2 字面', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText(/S096g2/)).not.toBeInTheDocument()
    })
  })
})
