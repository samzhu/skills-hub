import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RequestBoardPage } from './RequestBoardPage'

/**
 * S096g2-T04 — RequestBoardPage AC-15 / AC-16 / AC-17 isolation tests。
 *
 * URL-aware fetch mock：
 * - GET /api/v1/requests → list (votes desc 預設)
 * - POST /api/v1/requests → 201 {id}
 * - POST /api/v1/requests/{id}/vote → {voted, voteCount}
 * - GET /api/v1/me → current user identity (LAB mode shape)
 * - GET /api/v1/notifications/unread-count → AppShell bell probe
 *
 * 取代 S103 disabled-button assertion（T04 ship 後 CTA 應啟用，舊 invariant
 * 隨 stub 退場）；S103 「no spec ID leak in user-facing strings」invariant
 * carry-forward 到 AC-15。
 */

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/requests']}>
        <Routes>
          <Route path="/requests" element={<RequestBoardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const meResponse = {
  sub: 'alice',
  roles: ['user'],
  groups: [],
  companyId: null,
  deptId: null,
  scope: '',
}

const sampleRequests = [
  {
    id: 'r1',
    title: 'k8s autoscaler skill',
    description: '需要 k8s HPA 自動建議',
    requesterId: 'alice',
    status: 'OPEN' as const,
    claimerId: null,
    fulfilledSkillId: null,
    voteCount: 12,
    createdAt: '2026-05-03T10:00:00Z',
    updatedAt: '2026-05-03T10:00:00Z',
  },
  {
    id: 'r2',
    title: 'terraform plan summarizer',
    description: '解析 plan 輸出',
    requesterId: 'bob',
    status: 'OPEN' as const,
    claimerId: null,
    fulfilledSkillId: null,
    voteCount: 5,
    createdAt: '2026-05-02T10:00:00Z',
    updatedAt: '2026-05-02T10:00:00Z',
  },
  {
    id: 'r3',
    title: 'sql linter',
    description: 'PostgreSQL 風格檢查',
    requesterId: 'carol',
    status: 'IN_PROGRESS' as const,
    claimerId: 'dave',
    fulfilledSkillId: null,
    voteCount: 3,
    createdAt: '2026-05-01T10:00:00Z',
    updatedAt: '2026-05-01T10:00:00Z',
  },
]

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RequestBoardPage (S096g2-T04)', () => {
  it('AC-15: 3 requests → 3 row + CTA 啟用 + S103 spec ID leak invariant', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(sampleRequests) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('k8s autoscaler skill')).toBeInTheDocument()
    })
    expect(screen.getByText('terraform plan summarizer')).toBeInTheDocument()
    expect(screen.getByText('sql linter')).toBeInTheDocument()

    // CTA 啟用（取代 S103 disabled assertion）
    const cta = screen.getByRole('button', { name: '發起新需求' })
    expect(cta).not.toBeDisabled()

    // S103 invariant carry-forward: 無 spec ID leak in user-facing strings
    expect(screen.queryByText(/S096g\d/)).not.toBeInTheDocument()
  })

  it('AC-16: 點 CTA 開 modal → fill+submit → POST /requests', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/api/v1/requests') && init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ id: 'new-r' }) } as Response)
      }
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    // S139：等 useAuth fetchMe resolve（AuthArea 從 skeleton 切到 avatar），AuthGatedButton
    // loading 期間 click 會走 login() redirect 而非 onClick → 必須等 authenticated 才點。
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '開啟使用者選單' })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: '發起新需求' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: '發起新需求' })).toBeInTheDocument()
    })

    fireEvent.change(screen.getByLabelText(/標題/), { target: { value: 'docker compose linter' } })
    fireEvent.change(screen.getByLabelText(/說明/), { target: { value: '檢查 compose.yaml 結構' } })
    fireEvent.click(screen.getByRole('button', { name: '送出' }))

    await waitFor(() => {
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const postCall = calls.find((c) => c[1]?.method === 'POST' && c[0].endsWith('/requests'))
      expect(postCall).toBeDefined()
      expect(postCall![1].body).toContain('"title":"docker compose linter"')
      expect(postCall![1].body).toContain('"description":"檢查 compose.yaml 結構"')
    })
  })

  it('AC-17: 點 vote 按鈕 → POST toggle + count 更新為 server 值', async () => {
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/vote') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ voted: true, voteCount: 13 }),
        } as Response)
      }
      if (url.includes('/api/v1/requests') && !url.includes('/vote')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([sampleRequests[0]]) } as Response)
      }
      if (url.includes('/api/v1/me')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(meResponse) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('k8s autoscaler skill')).toBeInTheDocument()
    })

    expect(screen.getByText('12')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '投票' }))

    await waitFor(() => {
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const postCall = calls.find((c) => c[1]?.method === 'POST' && c[0].includes('/vote'))
      expect(postCall).toBeDefined()
      expect(postCall![0]).toContain('/api/v1/requests/r1/vote')
    })

    await waitFor(() => {
      expect(screen.getByText('13')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '已投票，再點取消' })).toBeInTheDocument()
    })
  })
})
