import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { GroupsPage } from './GroupsPage'

const groupTree = [
  {
    id: 'g_acme01',
    parentId: null,
    kind: 'COMPANY',
    displayName: 'Acme',
    principalKey: 'group:g_acme01',
    children: [
      {
        id: 'g_cloud1',
        parentId: 'g_acme01',
        kind: 'DEPARTMENT',
        displayName: 'Cloud',
        principalKey: 'group:g_cloud1',
        children: [
          {
            id: 'g_plat01',
            parentId: 'g_cloud1',
            kind: 'TEAM',
            displayName: 'Platform Team',
            principalKey: 'group:g_plat01',
            children: [],
          },
        ],
      },
    ],
  },
]

const groupSearch = [
  {
    id: 'g_cloud1',
    principalKey: 'group:g_cloud1',
    kind: 'DEPARTMENT',
    displayName: 'Cloud',
    path: ['Acme', 'Cloud'],
    memberCount: 2,
  },
]

const fetchMock = vi.fn(async (url: string) => {
  const u = String(url)
  if (u.includes('/api/v1/groups/tree')) {
    return { ok: true, status: 200, json: async () => groupTree } as Response
  }
  if (u.includes('/api/v1/groups/search')) {
    return { ok: true, status: 200, json: async () => groupSearch } as Response
  }
  if (u.includes('/notifications/unread-count')) {
    return { ok: true, status: 200, json: async () => ({ count: 0 }) } as Response
  }
  if (u.includes('/api/v1/me')) {
    return { ok: true, status: 200, json: async () => ({ userId: 'u_admin', name: '管理者' }) } as Response
  }
  return { ok: true, status: 200, json: async () => ({}) } as Response
})

beforeEach(() => {
  fetchMock.mockClear()
  globalThis.fetch = fetchMock as unknown as typeof fetch
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/groups']}>
        <Routes>
          <Route path="/groups" element={<GroupsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('GroupsPage — S170 AC-13', () => {
  it('AC-13: 選 Cloud 後顯示子群組控制與成員清單，且可見文案皆為繁體中文', async () => {
    renderPage()

    await waitFor(() => screen.getByRole('treeitem', { name: /Acme/ }))
    fireEvent.click(screen.getByRole('treeitem', { name: /Cloud/ }))

    expect(screen.getByRole('heading', { name: 'Cloud' })).toBeInTheDocument()
    expect(screen.getByText('Acme / Cloud')).toBeInTheDocument()
    expect(screen.getByText('Principal：group:g_cloud1')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: '新增子群組' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '新增成員' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '封存群組' })).toBeInTheDocument()
    expect(screen.getByText('子群組')).toBeInTheDocument()
    expect(screen.getAllByText('Platform Team')).toHaveLength(2)
    expect(screen.getByText('成員')).toBeInTheDocument()
    expect(await screen.findByText('目前有 2 位直接成員')).toBeInTheDocument()
  })
})
