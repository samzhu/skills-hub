import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from './AppShell'

/**
 * AppShell tests — 7 nav links + bell badge with poll-driven unread count。
 */

const renderWithCount = (count: number, initialPath = '/browse') => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ count }),
  } as Response)
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

  it('AC-3: zero unread → no badge', () => {
    renderWithCount(0)
    // bell aria-label always present
    expect(screen.getByLabelText('Notifications')).toBeInTheDocument()
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
})
