import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MySkillsPage } from './MySkillsPage'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

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

describe('MySkillsPage — S144 delete skill UX', () => {
  const skill = {
    id: 'skill-1',
    name: '刪除測試技能',
    description: '可被作者刪除',
    category: 'Testing',
    status: 'PUBLISHED',
    riskLevel: 'LOW',
    latestVersion: '1.0.0',
    downloadCount: 3,
  }

  const setSkillsFetchMock = (deleteResponse: Response) => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/me/flags-summary')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ openCount: 0 }) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ userId: 'u_alice0', handle: 'alice', sub: 'alice', name: 'Alice', email: 'alice@example.com', picture: null, roles: ['user'], groups: [], companyId: null, deptId: null, scope: '' }) } as Response)
      }
      if (url.includes('/api/v1/skills/skill-1/stats')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([0, 1, 2]) } as Response)
      }
      if (url.endsWith('/api/v1/skills/skill-1') && init?.method === 'DELETE') {
        return Promise.resolve(deleteResponse)
      }
      if (url.includes('/api/v1/skills')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ content: [skill], page: { number: 0, size: 200, totalPages: 1, totalElements: 1 } }) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
    })
  }

  it('AC-S144-5: 確認刪除成功後 row 消失並顯示成功 toast', async () => {
    setSkillsFetchMock({ ok: true, status: 204 } as Response)
    renderPage()

    await waitFor(() => expect(screen.getByText('刪除測試技能')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '刪除測試技能的動作' }))
    fireEvent.click(screen.getByRole('menuitem', { name: '刪除' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('確定要刪除「刪除測試技能」嗎？')

    fireEvent.click(screen.getByRole('button', { name: '確認刪除' }))

    await waitFor(() => expect(screen.queryByText('刪除測試技能')).not.toBeInTheDocument())
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith('技能已刪除')
  })

  it('AC-S144-5: row action menu keeps detail navigation and delete command separate', async () => {
    setSkillsFetchMock({ ok: true, status: 204 } as Response)
    renderPage()

    await waitFor(() => expect(screen.getByText('刪除測試技能')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '刪除測試技能的動作' }))

    const view = screen.getByRole('menuitem', { name: '檢視' })
    expect(view.closest('a')).toHaveAttribute('href', '/skills/skill-1')
    expect(screen.getByRole('menuitem', { name: '刪除' })).toBeInTheDocument()
  })

  it('AC-S144-5: 刪除 403/404 失敗顯示繁中錯誤 toast', async () => {
    setSkillsFetchMock({
      ok: false,
      status: 403,
      json: () => Promise.resolve({ error: 'FORBIDDEN', message: 'Forbidden' }),
    } as Response)
    renderPage()

    await waitFor(() => expect(screen.getByText('刪除測試技能')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '刪除測試技能的動作' }))
    fireEvent.click(screen.getByRole('menuitem', { name: '刪除' }))
    fireEvent.click(screen.getByRole('button', { name: '確認刪除' }))

    const { toast } = await import('sonner')
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('刪除失敗：沒有權限執行此操作。'))
  })
})

describe('MySkillsPage — S145 subscription management tab', () => {
  const me = {
    userId: 'u_alice0',
    handle: 'alice',
    sub: 'alice',
    name: 'Alice',
    email: 'alice@example.com',
    picture: null,
    roles: ['user'],
    groups: [],
    companyId: null,
    deptId: null,
    scope: '',
  }

  const setSubscriptionFetchMock = (subscriptions: unknown[]) => {
    let currentSubscriptions = [...subscriptions] as any[]
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/me/flags-summary')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ openCount: 0 }) } as Response)
      }
      if (url.endsWith('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(me) } as Response)
      }
      if (url.includes('/api/v1/me/subscriptions/details')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(currentSubscriptions) } as Response)
      }
      if (url.includes('/api/v1/me/subscriptions')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(currentSubscriptions.map((s: any) => s.skillId)) } as Response)
      }
      if (url.endsWith('/api/v1/skills/skill-1/subscribe') && init?.method === 'DELETE') {
        currentSubscriptions = currentSubscriptions.filter((s: any) => s.skillId !== 'skill-1')
        return Promise.resolve({ ok: true, status: 204 } as Response)
      }
      if (url.includes('/api/v1/skills')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ content: [], page: { number: 0, size: 200, totalPages: 0, totalElements: 0 } }) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
    })
  }

  it('AC-S145-1: 訂閱 tab 顯示 skill card 欄位', async () => {
    setSubscriptionFetchMock([
      {
        skillId: 'skill-1',
        skillName: 'deep-research',
        author: 'u_author1',
        authorDisplayName: 'Sam Zhu',
        latestVersion: '1.2.0',
        riskLevel: 'LOW',
        status: 'PUBLISHED',
        subscribedAt: '2026-05-08T10:15:30Z',
      },
      {
        skillId: 'skill-2',
        skillName: 'docker-helper',
        author: 'u_author2',
        authorDisplayName: 'Docker Author',
        latestVersion: '2.0.0',
        riskLevel: 'MEDIUM',
        status: 'PUBLISHED',
        subscribedAt: '2026-05-07T10:15:30Z',
      },
    ])

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /訂閱/ }))

    expect(await screen.findByText('deep-research')).toBeInTheDocument()
    expect(screen.getByText('docker-helper')).toBeInTheDocument()
    expect(screen.getByText('Sam Zhu')).toBeInTheDocument()
    expect(screen.getByText('v1.2.0')).toBeInTheDocument()
    expect(screen.getAllByText('低風險')[0]).toBeInTheDocument()
    expect(screen.getByText(/2026-05-08/)).toBeInTheDocument()
  })

  it('AC-S145-3: 點取消訂閱後呼叫 DELETE、移除 card、顯示 toast', async () => {
    setSubscriptionFetchMock([
      {
        skillId: 'skill-1',
        skillName: 'deep-research',
        author: 'u_author1',
        authorDisplayName: 'Sam Zhu',
        latestVersion: '1.2.0',
        riskLevel: 'LOW',
        status: 'PUBLISHED',
        subscribedAt: '2026-05-08T10:15:30Z',
      },
    ])

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /訂閱/ }))
    expect(await screen.findByText('deep-research')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '取消訂閱 deep-research' }))

    await waitFor(() => expect(screen.queryByText('deep-research')).not.toBeInTheDocument())
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/v1/skills/skill-1/subscribe', { method: 'DELETE' })
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith('已取消訂閱')
  })

  it('AC-S145-4: 無訂閱時顯示 empty state 和前往瀏覽', async () => {
    setSubscriptionFetchMock([])

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /訂閱/ }))

    expect(await screen.findByText('尚未訂閱任何技能')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '前往瀏覽' })).toHaveAttribute('href', '/')
  })
})
