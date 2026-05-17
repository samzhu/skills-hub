import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PublishReviewPage } from './PublishReviewPage'

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/publish/review?id=s1']}>
        <Routes>
          <Route path="/publish/review" element={<PublishReviewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('/api/v1/skills/s1')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            id: 's1',
            name: '產生字幕檔',
            description: '把影片轉成字幕',
            author: 'u_f7eb3a',
            authorDisplayName: 'Sam Zhu',
            authorHandle: null,
            category: 'video',
            latestVersion: '1',
            riskLevel: 'LOW',
            status: 'PUBLISHED',
            visibility: 'PUBLIC',
            downloadCount: 0,
            averageRating: 0,
            reviewCount: 0,
            createdAt: '2026-05-17T00:00:00Z',
            updatedAt: '2026-05-17T00:00:00Z',
            verified: true,
            latestVersionPublishedAt: '2026-05-17T00:00:00Z',
            license: null,
            compatibility: [],
            versionCount: 1,
            openFlagCount: 0,
          }),
      } as Response)
    }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
  })
})

describe('PublishReviewPage — S192 author display', () => {
  it('AC-S192-1: 作者 row 顯示 authorDisplayName 而不是 raw platform user id', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('產生字幕檔')).toBeInTheDocument())
    expect(screen.getByText('Sam Zhu')).toBeInTheDocument()
    expect(screen.queryByText('u_f7eb3a')).not.toBeInTheDocument()
  })
})
