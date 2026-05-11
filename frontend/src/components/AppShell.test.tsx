import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from './AppShell'

/**
 * AppShell tests — 7 nav links + bell badge with poll-driven unread count
 * + S139 AuthArea + bell conditional rendering on auth.status。
 */

/**
 * Mock fetch — 分流 by URL：
 *   - /api/v1/me → 200 AuthUser（authenticated）or 401（anonymous）依 `authenticated` 旗
 *   - /api/v1/notifications/unread-count → 200 {count}
 *   - 其他 → 預設 200 {count}
 */
const setupFetch = (count: number, authenticated: boolean) => {
  globalThis.fetch = vi.fn().mockImplementation((url: string | URL) => {
    const u = url.toString()
    if (u.includes('/api/v1/me')) {
      return Promise.resolve(
        authenticated
          ? ({
              ok: true,
              status: 200,
              json: () =>
                Promise.resolve({
                  sub: 'alice',
                  email: 'alice@example.com',
                  name: 'Alice',
                }),
            } as Response)
          : ({
              ok: false,
              status: 401,
              json: () => Promise.resolve({ error: 'unauthorized' }),
            } as Response),
      )
    }
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ count }),
    } as Response)
  })
}

const renderWithCount = (count: number, initialPath = '/browse') => {
  setupFetch(count, true) // 預設 authenticated 維持既有 6 個 case 行為
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AppShell><div>main content</div></AppShell>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const renderAnonymous = (initialPath = '/browse') => {
  setupFetch(0, false)
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AppShell><div>main content</div></AppShell>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AppShell — S096h1 nav + bell badge', () => {
  it('AC-1: brand「Skills Hub」renders + 7 nav links', () => {
    renderWithCount(0)
    expect(screen.getByText('Skills Hub')).toBeInTheDocument()
    // 7 nav labels per S096e1/f1/g1/h1/094a/094d
    ;['瀏覽', '集合', '需求', '我的技能', '發佈', '數據', '文件'].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument()
    })
  })

  it('AC-2: current path highlights matching nav link', () => {
    renderWithCount(0, '/publish')
    const publishLink = screen.getByText('發佈').closest('a')
    expect(publishLink?.className).toContain('text-foreground')
    expect(publishLink?.className).toContain('font-medium')
  })

  it('AC-3: zero unread → no badge', async () => {
    renderWithCount(0)
    // S139: bell 在 authenticated 後才渲染，需 waitFor
    await waitFor(() => {
      expect(screen.getByLabelText('通知')).toBeInTheDocument()
    })
    // 數字 badge 不應存在
    expect(screen.queryByText('1')).not.toBeInTheDocument()
  })

  it('AC-4: positive unread → badge with count', async () => {
    renderWithCount(7)
    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument()
    })
  })

  it('AC-5: count > 99 → 「99+」display', async () => {
    renderWithCount(123)
    await waitFor(() => {
      expect(screen.getByText('99+')).toBeInTheDocument()
    })
  })

  it('AC-6: children render in <main>', () => {
    renderWithCount(0)
    expect(screen.getByText('main content')).toBeInTheDocument()
  })

  // S139 — AuthArea + bell conditional cases

  it('S139 AC-1: anonymous → 顯示「登入」按鈕；bell 不渲染', async () => {
    renderAnonymous()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '登入' })).toBeInTheDocument()
    })
    expect(screen.queryByLabelText('通知')).toBeNull()
  })

  it('S139 AC-4: authenticated → AuthArea 渲染 avatar trigger（aria-haspopup=menu）', async () => {
    renderWithCount(0)
    await waitFor(() => {
      const avatarBtn = screen.getByRole('button', { name: /開啟使用者選單/i })
      expect(avatarBtn.getAttribute('aria-haspopup')).toBe('menu')
    })
  })

  it('S139 AC-7: anonymous → bell 不渲染', async () => {
    renderAnonymous()
    // 給 query effect 跑一輪
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '登入' })).toBeInTheDocument()
    })
    expect(screen.queryByLabelText('通知')).toBeNull()
  })
})
