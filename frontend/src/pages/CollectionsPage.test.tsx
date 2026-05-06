import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CollectionsPage } from './CollectionsPage'

/**
 * S096f2-T04 — CollectionsPage tests covering AC-10/11/12（spec ship 最後一塊；
 * 取代 S096f1 stub disabled-CTA assertion + S103 spec ID leak assertion 仍 carry-forward）。
 *
 * URL-aware fetchMock 對齊 NotificationsPage.test.tsx 既驗 pattern：
 * - GET /collections → 動態 list（依 state.collections）
 * - POST /collections → push 一筆並 echo {id}
 * - POST /collections/{id}/install → 回 {downloadUrls}
 * - GET /unread-count → {count: 0}（AppShell bell badge）
 */

const sampleCollections = [
  {
    // S118: rename installs → installCount 對齊 backend CollectionSummary
    // S096f3: maxRiskLevel HIGH（含 HIGH risk skill）
    id: 'c1', name: 'DevOps Starter', description: 'k8s + terraform tooling',
    skillCount: 3, installCount: 12, category: 'DevOps', createdAt: '2026-04-30T10:00:00Z',
    maxRiskLevel: 'HIGH',
  },
  {
    // S096f3: maxRiskLevel LOW
    id: 'c2', name: 'Frontend Quality', description: 'a11y + lint + i18n suite',
    skillCount: 5, installCount: 8, category: 'Frontend', createdAt: '2026-04-29T10:00:00Z',
    maxRiskLevel: 'LOW',
  },
  {
    // S096f3: maxRiskLevel null（尚未掃描）
    id: 'c3', name: 'Unscanned Pack', description: 'skills not yet scanned',
    skillCount: 2, installCount: 0, category: 'Misc', createdAt: '2026-04-28T10:00:00Z',
    maxRiskLevel: null,
  },
]

interface FetchMockState {
  collections: typeof sampleCollections
  postedCreates: Array<{ name: string; description: string | null; category: string; skillIds: string[] }>
  postedInstalls: string[]
  installResponse: { downloadUrls: string[] }
}

let state: FetchMockState

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const u = String(url)
  const method = init?.method ?? 'GET'

  if (u.includes('/notifications/unread-count')) {
    return { ok: true, status: 200, json: async () => ({ count: 0 }) } as Response
  }

  // S139: AuthArea / AuthGatedButton 透過 useAuth → fetchMe 探 /api/v1/me；
  // 預設 authenticated 維持既有 AC-10/11/12 點擊行為（lazy gate 通過）
  if (u.includes('/api/v1/me')) {
    return { ok: true, status: 200, json: async () => ({ sub: 'alice', email: 'alice@example.com' }) } as Response
  }

  // POST /collections/{id}/install
  const installMatch = u.match(/\/collections\/([^/?]+)\/install$/)
  if (installMatch && method === 'POST') {
    state.postedInstalls.push(installMatch[1])
    return { ok: true, status: 200, json: async () => state.installResponse } as Response
  }

  // POST /collections (create)
  if (u.endsWith('/collections') && method === 'POST') {
    const body = JSON.parse(String(init?.body ?? '{}'))
    state.postedCreates.push(body)
    const newId = 'c-new-' + (state.postedCreates.length)
    state.collections = [
      ...state.collections,
      {
        id: newId, name: body.name, description: body.description ?? '',
        skillCount: body.skillIds?.length ?? 0, installCount: 0,
        category: body.category, createdAt: '2026-05-03T10:00:00Z',
      },
    ]
    return { ok: true, status: 201, json: async () => ({ id: newId }) } as Response
  }

  // GET /collections (list)
  if (u.includes('/collections')) {
    return { ok: true, status: 200, json: async () => state.collections } as Response
  }

  return { ok: true, status: 200, json: async () => ({}) } as Response
})

beforeEach(() => {
  state = {
    collections: structuredClone(sampleCollections),
    postedCreates: [],
    postedInstalls: [],
    installResponse: { downloadUrls: ['/api/v1/skills/sk-1/download', '/api/v1/skills/sk-2/download', '/api/v1/skills/sk-3/download'] },
  }
  fetchMock.mockClear()
  global.fetch = fetchMock as unknown as typeof fetch
})

const renderPage = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/collections']}>
        <Routes>
          <Route path="/collections" element={<CollectionsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CollectionsPage — AC-10 CTA enable + AC-11 create + AC-12 install', () => {
  it('AC-10: 「建立集合」CTA active；點擊開 modal', async () => {
    renderPage()
    await waitFor(() => screen.getByText('DevOps Starter'))

    const btn = screen.getByRole('button', { name: /建立集合/ })
    expect(btn).not.toBeDisabled()

    fireEvent.click(btn)
    expect(await screen.findByRole('dialog', { name: '建立集合' })).toBeInTheDocument()
  })

  it('AC-11: Modal 填 form + submit → POST /collections + modal close + list refetch', async () => {
    renderPage()
    await waitFor(() => screen.getByText('DevOps Starter'))

    fireEvent.click(screen.getByRole('button', { name: /建立集合/ }))
    await screen.findByRole('dialog', { name: '建立集合' })

    fireEvent.change(screen.getByLabelText(/名稱/), { target: { value: 'Security Pack' } })
    fireEvent.change(screen.getByLabelText(/說明/), { target: { value: 'audit + scan tools' } })
    fireEvent.change(screen.getByLabelText(/分類/), { target: { value: 'Security' } })
    fireEvent.change(screen.getByLabelText(/技能 ID/), { target: { value: 'sk-1\nsk-2\nsk-3' } })

    fireEvent.click(screen.getByRole('button', { name: '送出' }))

    await waitFor(() => {
      expect(state.postedCreates).toHaveLength(1)
    })
    expect(state.postedCreates[0]).toMatchObject({
      name: 'Security Pack',
      description: 'audit + scan tools',
      category: 'Security',
      skillIds: ['sk-1', 'sk-2', 'sk-3'],
    })

    // modal close + list refetch 後新 collection 出現
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '建立集合' })).not.toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByText('Security Pack')).toBeInTheDocument()
    })
  })

  it('AC-12: Install card → POST /{id}/install + loop trigger N 個 <a download> click', async () => {
    // Spy on anchor click — JSDOM document.body.appendChild 後 a.click 真會 fire 但不真下載
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click')

    renderPage()
    await waitFor(() => screen.getByText('DevOps Starter'))

    // 第一張 card (DevOps Starter, c1) 的 install button
    const installButtons = screen.getAllByRole('button', { name: /安裝/ })
    fireEvent.click(installButtons[0])

    await waitFor(() => {
      expect(state.postedInstalls).toEqual(['c1'])
    })
    // 50ms 間隔 × 3 個 download → 等 200ms 確保全部 fire
    await new Promise((resolve) => setTimeout(resolve, 250))
    expect(clickSpy).toHaveBeenCalledTimes(3)

    clickSpy.mockRestore()
  })

  it('AC-S103 carry-forward: user-facing copy 不洩漏 internal spec ID S096f2', async () => {
    renderPage()
    await waitFor(() => screen.getByText('DevOps Starter'))
    expect(screen.queryByText(/S096f2/)).not.toBeInTheDocument()
    expect(screen.queryByText(/即將開放/)).not.toBeInTheDocument() // S096f1 stub copy 已退場
  })
})

/**
 * S096f3 — Risk filter AC-2 / AC-3 tests。
 * 對齊 spec §3 AC-2「篩選後只顯示對應集合」+ AC-3「null maxRiskLevel filter 時不顯示」。
 */
describe('CollectionsPage — S096f3 Risk Filter', () => {
  it('AC-2(S096f3): 選 LOW filter → 只顯示 maxRiskLevel=LOW 的集合', async () => {
    renderPage()
    // wait for all 3 collections to appear
    await waitFor(() => screen.getByText('DevOps Starter'))
    expect(screen.getByText('Frontend Quality')).toBeInTheDocument()
    expect(screen.getByText('Unscanned Pack')).toBeInTheDocument()

    // click 「低風險」filter button
    fireEvent.click(screen.getByText('低風險'))

    // only Frontend Quality (LOW) visible; DevOps Starter (HIGH) + Unscanned Pack (null) excluded
    expect(screen.getByText('Frontend Quality')).toBeInTheDocument()
    expect(screen.queryByText('DevOps Starter')).not.toBeInTheDocument()
    expect(screen.queryByText('Unscanned Pack')).not.toBeInTheDocument()
  })

  it('AC-3(S096f3): null maxRiskLevel 集合 — 選任一 filter 時不顯示', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Unscanned Pack'))

    // activate any filter
    fireEvent.click(screen.getByText('高風險'))

    // Unscanned Pack (maxRiskLevel=null) must be excluded
    expect(screen.queryByText('Unscanned Pack')).not.toBeInTheDocument()

    // clear filter → Unscanned Pack re-appears
    fireEvent.click(screen.getByText('全部'))
    expect(screen.getByText('Unscanned Pack')).toBeInTheDocument()
  })
})
