import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MySkillsPage } from './MySkillsPage'

// S110 — verify MySkillsPage user-facing labels 全 zh-TW（per CLAUDE.md「UI 語言: 繁體中文」）

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/my-skills']}>
        <Routes>
          <Route path="/my-skills" element={<MySkillsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// S132: MySkillsPage 加 page-level auth gate（!author / meError → EmptyState 登入提示）後，
// 既有 zh-TW label compliance 測試需 mock fetch 讓 /me 回 valid sub（保留 metric labels 出現）
const setAuthedFetchMock = () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
    if (url.includes('/api/v1/me/flags-summary')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ openCount: 0 }) } as Response)
    }
    if (url.includes('/api/v1/me')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ userId: 'u_alice0', handle: 'alice', sub: 'alice', name: 'Alice', email: 'alice@example.com', picture: null, roles: ['user'], groups: [], companyId: null, deptId: null, scope: '' }) } as Response)
    }
    if (url.includes('/api/v1/skills')) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ content: [], page: { number: 0, size: 200, totalPages: 0, totalElements: 0 } }) } as Response)
    }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setAuthedFetchMock()
})

describe('MySkillsPage — S110 zh-TW label compliance', () => {
  it('AC-1: 3 個 metric cards 用 zh-TW labels（S112-T04 移除平均評分後）', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('技能總數')).toBeInTheDocument()
    })
    expect(screen.getByText('下載總數')).toBeInTheDocument()
    expect(screen.getByText('待處理回報')).toBeInTheDocument()
  })

  it('AC-2: status subtitle 不再含 published/draft/suspended 英文 token', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText(/published.*draft.*suspended/)).not.toBeInTheDocument()
    })
  })

  it('AC-3: 既有 English labels 已完全移除（regression guard）', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.queryByText('Total skills')).not.toBeInTheDocument()
    })
    expect(screen.queryByText('Total downloads')).not.toBeInTheDocument()
    expect(screen.queryByText('Avg rating')).not.toBeInTheDocument()
    expect(screen.queryByText('Open flags')).not.toBeInTheDocument()
  })

  it('AC-S132-1: 未登入（fetch 失敗）→ EmptyState「請先登入後查看自己發布的技能」', async () => {
    // Override default fetch mock for this test only — simulate auth failure
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({ error: 'unauthorized' }) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
    })
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('請先登入後查看自己發布的技能')).toBeInTheDocument()
    })
    // 未登入時不顯 metric cards / tabs / 「以...身份發布」
    expect(screen.queryByText('技能總數')).not.toBeInTheDocument()
    expect(screen.queryByText(/身份發布/)).not.toBeInTheDocument()
  })
})

describe('MySkillsPage — Flags wiring (S112-T04)', () => {
  it('S112 AC-3: 待處理回報 MetricCard 顯示 useFlagsSummary openCount', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/me/flags-summary')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ openCount: 2 }) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ userId: 'u_alice0', handle: 'alice', sub: 'alice', name: 'Alice', email: 'alice@example.com', picture: null, roles: ['user'], groups: [], companyId: null, deptId: null, scope: '' }) } as Response)
      }
      if (url.includes('/api/v1/skills')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ content: [], page: { number: 0, size: 200, totalPages: 0, totalElements: 0 } }) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
    })
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('待處理回報')).toBeInTheDocument()
    })
    // openCount 從 useFlagsSummary 注入；subtitle 為「未處理 OPEN 狀態」
    await waitFor(() => {
      expect(screen.getByText('未處理 OPEN 狀態')).toBeInTheDocument()
    })
    // 在「待處理回報」MetricCard 內部找 value "2"（避開 DOM 內其他「2」字元）
    const card = screen.getByText('待處理回報').closest('div')
    if (!card) throw new Error('待處理回報 card 未找到')
    expect(within(card).getByText('2')).toBeInTheDocument()
  })

  it('S112 AC-4: 「平均評分」MetricCard 不存在（grid 變 3-card）', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('技能總數')).toBeInTheDocument()
    })
    // 移除「平均評分」card 後不應出現此 label / subtitle
    expect(screen.queryByText('平均評分')).not.toBeInTheDocument()
    expect(screen.queryByText('評分系統未啟用')).not.toBeInTheDocument()
  })
})
