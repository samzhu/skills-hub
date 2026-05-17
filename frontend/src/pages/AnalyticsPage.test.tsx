import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AnalyticsPage } from './AnalyticsPage'
import type { OverviewStats } from '@/api/analytics'

/**
 * AnalyticsPage tests — S100e Top 10 link defensive guard。
 *
 * 對應 docs/grimo/specs/2026-05-02-S100e-analytics-link-defensive.md §3 ACs。
 * useOverview hook 用 vi.mock 換成 fixture provider，避開真實 fetch；focus on
 * render contract（link / non-link / placeholder）。
 */

const mockUseOverview = vi.fn()
vi.mock('@/hooks/useAnalytics', () => ({
  useOverview: () => mockUseOverview(),
}))

const renderWithProviders = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AnalyticsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const stats = (topSkills: OverviewStats['topSkills']): OverviewStats => ({
  totalSkills: 100,
  totalDownloads: 500,
  newSkillsThisWeek: 3,
  topSkills,
})

describe('AnalyticsPage — S100e Top 10 link defensive guard', () => {
  it('AC-1 (positive): valid author 渲染 <a> Link 至 /skills/:author/:name', () => {
    mockUseOverview.mockReturnValue({
      data: stats([{ name: 'x', author: 'alice', downloads: 5 }]),
      isLoading: false,
      error: null,
    })
    renderWithProviders()
    const link = screen.getByRole('link', { name: /x/ })
    expect(link).toHaveAttribute('href', '/skills/alice/x')
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('AC-2 (negative key missing): author 缺欄不產生 link 也不送 /skills/undefined/...', () => {
    mockUseOverview.mockReturnValue({
      data: stats([{ name: 'x', downloads: 5 } as OverviewStats['topSkills'][number]]),
      isLoading: false,
      error: null,
    })
    const { container } = renderWithProviders()
    expect(screen.queryByRole('link', { name: /x/ })).toBeNull()
    expect(screen.getByText('x')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    // 確保沒有任何 link 的 href 含 "undefined"
    const allLinks = Array.from(container.querySelectorAll('a'))
    expect(allLinks.some((a) => a.getAttribute('href')?.includes('undefined'))).toBe(false)
  })

  it('AC-3 (negative "undefined" string): author === "undefined" 字串也 fallback 為非 link', () => {
    mockUseOverview.mockReturnValue({
      data: stats([{ name: 'x', author: 'undefined', downloads: 5 }]),
      isLoading: false,
      error: null,
    })
    const { container } = renderWithProviders()
    expect(screen.queryByRole('link', { name: /x/ })).toBeNull()
    expect(screen.getByText('x')).toBeInTheDocument()
    const allLinks = Array.from(container.querySelectorAll('a'))
    expect(allLinks.some((a) => a.getAttribute('href') === '/skills/undefined/x')).toBe(false)
  })

  it('AC-4 (edge empty): topSkills [] 顯示「尚無下載記錄」placeholder', () => {
    mockUseOverview.mockReturnValue({
      data: stats([]),
      isLoading: false,
      error: null,
    })
    renderWithProviders()
    expect(screen.getByText('尚無下載記錄')).toBeInTheDocument()
  })

  it('AC-S192-4: raw author id 可在 link href，但不出現在排行榜可見文字', () => {
    mockUseOverview.mockReturnValue({
      data: stats([{ name: '產生字幕檔', author: 'u_f7eb3a', downloads: 5 }]),
      isLoading: false,
      error: null,
    })
    renderWithProviders()

    const link = screen.getByRole('link', { name: /產生字幕檔/ })
    expect(link).toHaveAttribute('href', '/skills/u_f7eb3a/產生字幕檔')
    expect(screen.queryByText('u_f7eb3a')).not.toBeInTheDocument()
  })

  it('S156 #3: 「熱門排行」hero metric card 已移除（與下方 leaderboard 重複）', () => {
    mockUseOverview.mockReturnValue({
      data: stats([{ name: 'x', author: 'alice', downloads: 5 }]),
      isLoading: false,
      error: null,
    })
    renderWithProviders()
    expect(screen.queryByText('熱門排行')).toBeNull()
    // hero metric strip 從 4-up 變 3-up（總技能數 / 總下載次數 / 本週新增）
    expect(screen.getByText('總技能數')).toBeInTheDocument()
    expect(screen.getByText('總下載次數')).toBeInTheDocument()
    expect(screen.getByText('本週新增')).toBeInTheDocument()
  })
})
