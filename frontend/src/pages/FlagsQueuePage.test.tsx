import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FlagsQueuePage } from './FlagsQueuePage'

/**
 * S098e3-T04 — FlagsQueuePage isolation tests。
 *
 * URL-aware fetch mock：
 * - GET /api/v1/flags?status=OPEN → flags array
 * - PATCH /api/v1/skills/{id}/flags/{flagId} → 204
 * - GET /api/v1/notifications/unread-count → AppShell bell badge probe（fallback {count:0}）
 */

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/flags']}>
        <Routes>
          <Route path="/flags" element={<FlagsQueuePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('FlagsQueuePage (S098e3-T04)', () => {
  it('S098e3 AC-11: list OPEN flags 含 Resolve / Dismiss buttons', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/flags?status=OPEN')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([
          {
            id: 'f1', skillId: 'sk-1', type: 'malicious', description: '此 skill 含後門',
            reportedBy: 'alice', createdAt: '2026-05-03T10:00:00Z', status: 'OPEN',
          },
          {
            id: 'f2', skillId: 'sk-2', type: 'spam', description: '重複內容',
            reportedBy: 'bob', createdAt: '2026-05-03T11:00:00Z', status: 'OPEN',
          },
          {
            id: 'f3', skillId: 'sk-3', type: 'security', description: null,
            reportedBy: 'carol', createdAt: '2026-05-03T12:00:00Z', status: 'OPEN',
          },
        ]) } as Response)
      }
      // AppShell bell badge 預設 0
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()

    // 3 個 flag row 應渲染
    await waitFor(() => {
      expect(screen.getByText('此 skill 含後門')).toBeInTheDocument()
    })
    expect(screen.getByText('重複內容')).toBeInTheDocument()
    // type 中譯顯
    expect(screen.getByText('惡意指令')).toBeInTheDocument()
    expect(screen.getByText('垃圾內容')).toBeInTheDocument()
    expect(screen.getByText('資安疑慮')).toBeInTheDocument()
    // 每 row 含 Resolve + Dismiss 按鈕（3 row × 2 = 6）
    expect(screen.getAllByRole('button', { name: 'Resolve' })).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: 'Dismiss' })).toHaveLength(3)
    // skill link 跳 SkillDetail
    const skillLinks = screen.getAllByRole('link', { name: /sk-/ })
    expect(skillLinks[0]).toHaveAttribute('href', '/skills/sk-1')
  })

  it('S098e3 AC-12: 點 Resolve 觸發 PATCH status=RESOLVED', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return Promise.resolve({ ok: true, status: 204, json: () => Promise.resolve(null) } as Response)
      }
      if (url.includes('/flags?status=OPEN')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([
          {
            id: 'f1', skillId: 'sk-1', type: 'malicious', description: 'x',
            reportedBy: 'alice', createdAt: '2026-05-03T10:00:00Z', status: 'OPEN',
          },
        ]) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ count: 0 }) } as Response)
    })

    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))

    // PATCH 應觸發到 /api/v1/skills/sk-1/flags/f1
    await waitFor(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const patchCall = calls.find((c) => c[1]?.method === 'PATCH')
      expect(patchCall).toBeDefined()
      expect(patchCall![0]).toContain('/api/v1/skills/sk-1/flags/f1')
      expect(patchCall![1].body).toContain('"status":"RESOLVED"')
    })
  })
})
