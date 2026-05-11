import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CollectionDetailPage } from './CollectionDetailPage'

/** S150-T05 — CollectionDetailPage AC-1~AC-6 */

const sampleDetail = {
  id: 'c1',
  name: 'Security Audit Pack',
  description: 'Audit tools for terraform and k8s',
  category: 'Security',
  ownerId: 'alice',
  installCount: 5,
  createdAt: '2026-05-01T00:00:00Z',
  skills: [
    { id: 'sk-1', name: 'tf-scanner', category: 'Security', riskLevel: 'HIGH', latestVersion: '1.0.0' },
    { id: 'sk-2', name: 'k8s-audit', category: 'DevOps', riskLevel: 'MEDIUM', latestVersion: '2.1.0' },
  ],
}

const notFoundResponse = { ok: false, status: 404, json: async () => ({ message: 'Not found' }) } as Response

let collectionResponse: Response

const fetchMock = vi.fn(async (url: string) => {
  const u = String(url)
  if (u.includes('/notifications/unread-count')) {
    return { ok: true, status: 200, json: async () => ({ count: 0 }) } as Response
  }
  if (u.includes('/api/v1/me')) {
    return { ok: true, status: 200, json: async () => ({ sub: 'alice', email: 'alice@example.com' }) } as Response
  }
  if (u.match(/\/collections\/[^/?]+$/)) {
    return collectionResponse
  }
  return { ok: true, status: 200, json: async () => ({}) } as Response
})

beforeEach(() => {
  fetchMock.mockClear()
  collectionResponse = { ok: true, status: 200, json: async () => sampleDetail } as Response
  globalThis.fetch = fetchMock as unknown as typeof fetch
})

function renderPage(id = 'c1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/collections/${id}`]}>
        <Routes>
          <Route path="/collections/:id" element={<CollectionDetailPage />} />
          <Route path="/collections" element={<div>collections list</div>} />
          <Route path="/skills/:id" element={<div>skill detail</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CollectionDetailPage', () => {
  it('AC-1: shows collection name and all skills', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Security Audit Pack'))
    expect(screen.getByText('tf-scanner')).toBeInTheDocument()
    expect(screen.getByText('k8s-audit')).toBeInTheDocument()
  })

  it('AC-2: each skill row is a link to /skills/:skillId', async () => {
    renderPage()
    await waitFor(() => screen.getByText('tf-scanner'))
    const link = screen.getByText('tf-scanner').closest('a')
    expect(link).toHaveAttribute('href', '/skills/sk-1')
    const link2 = screen.getByText('k8s-audit').closest('a')
    expect(link2).toHaveAttribute('href', '/skills/sk-2')
  })

  it('AC-3: back link points to /collections', async () => {
    renderPage()
    await waitFor(() => screen.getByText('集合列表'))
    const back = screen.getByText('集合列表').closest('a')
    expect(back).toHaveAttribute('href', '/collections')
  })

  it('AC-4: install button renders with correct skill count', async () => {
    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /安裝 2 個技能/ }))
  })

  it('AC-5: 404 response shows error empty state', async () => {
    collectionResponse = notFoundResponse
    renderPage('nonexistent')
    await waitFor(() => screen.getByText('找不到此集合。'))
  })

  it('AC-6: skillCount=0 shows empty state message', async () => {
    collectionResponse = {
      ok: true,
      status: 200,
      json: async () => ({ ...sampleDetail, skills: [] }),
    } as Response
    renderPage()
    await waitFor(() => screen.getByText('此集合目前無技能。'))
  })
})
