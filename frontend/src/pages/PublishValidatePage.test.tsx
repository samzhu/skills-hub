import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PublishValidatePage } from './PublishValidatePage'

/**
 * S098a — PublishValidatePage tests。
 *
 * 此頁 polling /skills/{id}：scanning (riskLevel=null) 狀態顯 stepper +
 * 黃色 scanning callout；scan 完成（riskLevel 設值）會 useEffect navigate
 * 到 /publish/review?id=X — 此 transition 用 sentinel route 驗 navigate
 * 是否觸發。
 */

const skillScanning: {
  id: string
  name: string
  author: string
  description: string
  category: string
  riskLevel: string | null
  status: string
  downloadCount: number
  latestVersion: string
  createdAt: string
  updatedAt: string
} = {
  id: 'skill-1',
  name: 'date-formatter',
  author: 'team-a',
  description: 'desc',
  category: 'utility',
  riskLevel: null,
  status: 'PUBLISHED',
  downloadCount: 0,
  latestVersion: '1',
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-01T00:00:00Z',
}

const skillDone = {
  ...skillScanning,
  riskLevel: 'LOW',
}

const renderWith = (search: string, skill: typeof skillScanning) => {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve(skill),
  } as Response)
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/publish/validate${search}`]}>
        <Routes>
          <Route path="/publish/validate" element={<PublishValidatePage />} />
          {/* Sentinel route — useEffect navigate redirect 後可斷言此 route 被命中 */}
          <Route path="/publish/review" element={<div>REDIRECTED_TO_REVIEW</div>} />
          <Route path="/skills/:id" element={<div>REDIRECTED_TO_DETAIL</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('PublishValidatePage — S098a', () => {
  it('AC-1: missing id query renders error callout', () => {
    renderWith('', skillScanning)
    expect(screen.getByText(/缺少 skill id 參數/)).toBeInTheDocument()
  })

  it('AC-2: scanning state (riskLevel=null) renders stepper + scanning callout', async () => {
    renderWith('?id=skill-1', skillScanning)
    // h1
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: '驗證進行中' })).toBeInTheDocument()
    })
    // Step labels visible
    expect(screen.getByText('上傳')).toBeInTheDocument()
    expect(screen.getByText('驗證')).toBeInTheDocument()
    expect(screen.getByText('掃描')).toBeInTheDocument()
    expect(screen.getByText('發佈結果')).toBeInTheDocument()
    // Scanning callout includes 「進行中」+ skill name
    await waitFor(() => {
      expect(screen.getByText(/風險掃描進行中/)).toBeInTheDocument()
    })
  })

  it('AC-3: scan complete (riskLevel set) triggers redirect to /publish/review', async () => {
    renderWith('?id=skill-1', skillDone)
    // useEffect 會 navigate；sentinel route 會 render
    await waitFor(() => {
      expect(screen.getByText('REDIRECTED_TO_REVIEW')).toBeInTheDocument()
    })
  })

  it('AC-S187-10: version 驗證完成後導回 detail', async () => {
    renderWith('?id=skill-1&mode=version', skillDone)

    await waitFor(() => {
      expect(screen.getByText('REDIRECTED_TO_DETAIL')).toBeInTheDocument()
    })
  })

  it('AC-S187-10: version flow 顯示新版本驗證中', async () => {
    renderWith('?id=skill-1&mode=version', skillScanning)

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: '新版本驗證中' })).toBeInTheDocument()
    })
  })

  it('AC-4: upload-strip renders skill metadata when skill loaded', async () => {
    renderWith('?id=skill-1', skillScanning)
    await waitFor(() => {
      // S098a3: filename derived from skill.name + version
      expect(screen.getByText('date-formatter-1.zip')).toBeInTheDocument()
    })
    expect(screen.getByText('✓ 已上傳')).toBeInTheDocument()
  })
})
