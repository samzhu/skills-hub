import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HomePage } from './HomePage'

// S104 — verify filter-active + 0 hits 三 UI signal 一致
//   AC-1 = EmptyState headline 含 selected tier label
//   AC-2 = 「清除篩選」button 清空 filter
//   AC-3 = count 區顯 filtered count（X 個技能（共 Y））
//   AC-4 = pagination hidden when filtered hits 0

// 假資料：3 LOW + 0 NONE，模擬 production DB 狀態（per S096c deferred Flyway migration runtime classify only）
const mockSkillsPage = {
  content: [
    { id: '1', name: 'sk1', author: 'a1', description: 'd1', category: 'Testing', riskLevel: 'LOW', latestVersion: '1.0.0', downloadCount: 0, status: 'PUBLISHED', createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z', aclEntries: ['*:read'] },
    { id: '2', name: 'sk2', author: 'a2', description: 'd2', category: 'Testing', riskLevel: 'LOW', latestVersion: '1.0.0', downloadCount: 0, status: 'PUBLISHED', createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z', aclEntries: ['*:read'] },
    { id: '3', name: 'sk3', author: 'a3', description: 'd3', category: 'Testing', riskLevel: 'LOW', latestVersion: '1.0.0', downloadCount: 0, status: 'PUBLISHED', createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z', aclEntries: ['*:read'] },
  ],
  page: { number: 0, size: 20, totalElements: 103, totalPages: 6 },
}

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HomePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  // 同 codebase pattern：直接 stub globalThis.fetch (與既有 SkillDetailPage.test.tsx 一致)
  ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('/api/v1/skills')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(mockSkillsPage) } as Response)
    }
    if (url.includes('/api/v1/categories')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
    }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
  })
})

describe('HomePage — S104 filter-active 0-hits UX', () => {
  it('AC-baseline: 無 filter 時顯 unfiltered count + render skill cards', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/共 103 個技能/)).toBeInTheDocument()
    })
    expect(screen.getByText('sk1')).toBeInTheDocument()
  })

  it('AC-S106: 預設 sortMode=recommended 時 fetchSkills URL 含 sort=downloadCount,desc (S106 fix)', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('sk1')).toBeInTheDocument()
    })
    const fetchMock = (globalThis as any).fetch as ReturnType<typeof vi.fn>
    const skillsCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/api/v1/skills'))
    expect(skillsCalls.length).toBeGreaterThan(0)
    // 預設「推薦」chip 起始 active；S106 fix 後 URL 必須含 sort param 不再 fall-through
    const url = String(skillsCalls[0][0])
    expect(url).toMatch(/sort=downloadCount(%2C|,)desc/)
  })

  it('AC-1 + AC-3: 點「無風險」filter (DB 0 NONE) → EmptyState headline 含「無風險」+ count 顯「0 個技能（共 103）」', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('sk1')).toBeInTheDocument()
    })
    // 點 RiskFilterSidebar 的「無風險」button
    const noRiskBtn = screen.getByRole('button', { name: /無風險/ })
    fireEvent.click(noRiskBtn)
    await waitFor(() => {
      // AC-1: EmptyState headline 含 selected tier label
      expect(screen.getByRole('heading', { level: 2, name: /沒有「無風險」的技能/ })).toBeInTheDocument()
    })
    // AC-3: count 顯 filtered count + total context
    expect(screen.getByText(/0 個技能（共 103）/)).toBeInTheDocument()
  })

  it('AC-2: 點「清除篩選」button → filter 清空 + skills 回顯 + count 回 unfiltered', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('sk1')).toBeInTheDocument()
    })
    const noRiskBtn = screen.getByRole('button', { name: /無風險/ })
    fireEvent.click(noRiskBtn)
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 2, name: /沒有「無風險」的技能/ })).toBeInTheDocument()
    })
    const clearBtn = screen.getByRole('button', { name: /清除篩選/ })
    fireEvent.click(clearBtn)
    await waitFor(() => {
      expect(screen.queryByRole('heading', { level: 2, name: /沒有「無風險」的技能/ })).not.toBeInTheDocument()
    })
    expect(screen.getByText('sk1')).toBeInTheDocument()
    expect(screen.getByText(/共 103 個技能/)).toBeInTheDocument()
  })

  it('AC-S48: 切換排序模式時頁碼重置為第 1 頁', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('sk1')).toBeInTheDocument()
    })
    // 先翻到第 2 頁
    fireEvent.click(screen.getByRole('button', { name: '下一頁' }))
    await waitFor(() => {
      expect(screen.getByText(/第 2 \/ 6 頁/)).toBeInTheDocument()
    })
    // 切換排序 → 頁碼應重置為第 1 頁
    fireEvent.click(screen.getByRole('button', { name: '最新' }))
    await waitFor(() => {
      expect(screen.getByText(/第 1 \/ 6 頁/)).toBeInTheDocument()
    })
    // 確認 fetch 用 page=0
    const fetchMock = (globalThis as any).fetch as ReturnType<typeof vi.fn>
    const lastCall = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/api/v1/skills')).at(-1)
    expect(String(lastCall?.[0])).toContain('page=0')
  })

  it('AC-4: filter-active + 0 hits 時 pagination 隱藏', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('sk1')).toBeInTheDocument()
    })
    // baseline: pagination 顯（103 skills / 20 = 6 pages）
    expect(screen.getByText(/第 \d+ \/ 6 頁/)).toBeInTheDocument()
    // click filter 後 pagination 應消失
    const noRiskBtn = screen.getByRole('button', { name: /無風險/ })
    fireEvent.click(noRiskBtn)
    await waitFor(() => {
      expect(screen.queryByText(/第 \d+ \/ 6 頁/)).not.toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '上一頁' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下一頁' })).not.toBeInTheDocument()
  })

  it('AC-S144-6: 重新載入 /browse 後不渲染已被刪除的 skill', async () => {
    const deletedSkill = { id: 'deleted-skill', name: '已刪除技能', author: 'alice', description: 'deleted', category: 'Testing', riskLevel: 'LOW', latestVersion: '1.0.0', downloadCount: 0, status: 'PUBLISHED', createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z', aclEntries: ['*:read'] }
    const survivor = { id: 'survivor-skill', name: '保留技能', author: 'alice', description: 'survivor', category: 'Testing', riskLevel: 'LOW', latestVersion: '1.0.0', downloadCount: 0, status: 'PUBLISHED', createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z', aclEntries: ['*:read'] }
    let page = {
      content: [deletedSkill, survivor],
      page: { number: 0, size: 20, totalElements: 2, totalPages: 1 },
    }
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/skills')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(page) } as Response)
      }
      if (url.includes('/api/v1/categories')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
    })

    const first = renderPage()
    await waitFor(() => expect(screen.getByText('已刪除技能')).toBeInTheDocument())
    first.unmount()

    page = {
      content: [survivor],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
    }
    renderPage()

    await waitFor(() => expect(screen.getByText('保留技能')).toBeInTheDocument())
    expect(screen.queryByText('已刪除技能')).not.toBeInTheDocument()
  })
})
