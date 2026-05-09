import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LandingPage } from './LandingPage'

// S108 / S155 #1 — verify footer 「API」link
//   S108 originally: /swagger-ui/index.html
//   S155 #1 superseded: LAB profile 未啟用 SpringDoc → 改指向 /docs/rest-api 避免 swagger-ui 404

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<LandingPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('LandingPage — S108 footer API link UX', () => {
  it('AC-3 (S155 #1 supersedes S108): footer 「API」link 指向 /docs/rest-api (LAB SpringDoc 未啟用)', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('API')).toBeInTheDocument()
    })
    const apiLink = screen.getByText('API').closest('a')
    expect(apiLink).toHaveAttribute('href', '/docs/rest-api')
  })

  it('AC-baseline: footer 同時保留「文件」link (per S102 ship)', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('文件')).toBeInTheDocument()
    })
    const docsLink = screen.getByText('文件').closest('a')
    expect(docsLink).toHaveAttribute('href', '/docs/your-first-skill')
  })

  it('AC-baseline (S102): footer 移除「狀態」placeholder link', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText('狀態')).not.toBeInTheDocument()
    })
  })
})
