import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CreateCollectionModal } from './CreateCollectionModal'
import * as skillsApi from '@/api/skills'

vi.mock('@/api/skills', async () => {
  const actual = await vi.importActual<typeof import('@/api/skills')>('@/api/skills')
  return { ...actual, createCollection: vi.fn() }
})

const createCollectionMock = vi.mocked(skillsApi.createCollection)

const publishedSkills = [
  {
    id: 'skill-a',
    name: 'deep-research',
    description: 'Research workflows',
    author: 'u_alice0',
    category: 'Research',
    status: 'PUBLISHED',
    riskLevel: 'LOW',
    latestVersion: '1.0.0',
    downloadCount: 0,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
  },
  {
    id: 'skill-b',
    name: 'docker-helper',
    description: 'Docker workflows',
    author: 'u_alice0',
    category: 'DevOps',
    status: 'PUBLISHED',
    riskLevel: 'MEDIUM',
    latestVersion: '2.1.0',
    downloadCount: 0,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
  },
]

function mockFetch(skills = publishedSkills) {
  globalThis.fetch = vi.fn().mockImplementation((url: string | URL) => {
    const u = url.toString()
    if (u.includes('/api/v1/me')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ userId: 'u_alice0', handle: 'alice', sub: 'alice', email: 'alice@example.com', name: 'Alice', roles: [], groups: [], companyId: null, deptId: null, scope: '', picture: null }),
      } as Response)
    }
    if (u.includes('/api/v1/skills')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ content: skills, page: { number: 0, size: 100, totalPages: 1, totalElements: skills.length } }),
      } as Response)
    }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) } as Response)
  })
}

function renderModal(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <CreateCollectionModal onClose={onClose} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { onClose }
}

describe('CreateCollectionModal — S172 my-skills picker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    createCollectionMock.mockResolvedValue({ id: 'collection-1' })
    mockFetch()
  })

  it('AC-S172-8: create collection dialog has title description and close button', async () => {
    const { onClose } = renderModal()

    const dialog = await screen.findByRole('dialog', { name: '建立集合' })
    expect(within(dialog).getByText('從你已發布的技能挑選幾個，組成可一次安裝的技能包。')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '關閉建立集合' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('AC-S172-9: user can add a published my-skill from dropdown', async () => {
    renderModal()

    await waitFor(() => {
      const urls = vi.mocked(globalThis.fetch).mock.calls.map(([url]) => url.toString())
      expect(urls).toContain('/api/v1/skills?author=u_alice0&page=0&size=100')
    })
    fireEvent.change(await screen.findByLabelText('新增技能'), { target: { value: 'skill-a' } })
    fireEvent.click(screen.getByRole('button', { name: '新增' }))

    expect(screen.getByTestId('selected-skills-list')).toHaveTextContent('deep-research')
    expect(screen.getByLabelText('新增技能')).not.toHaveTextContent('deep-research')
  })

  it('AC-S172-10: no published skills disables submit and links to publish', async () => {
    mockFetch([{ ...publishedSkills[0], id: 'draft-1', status: 'DRAFT' }])
    renderModal()

    expect(await screen.findByText('集合只能加入已發布技能')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '前往發布技能' })).toHaveAttribute('href', '/publish')
    expect(screen.getByRole('button', { name: '建立集合' })).toBeDisabled()
  })

  it('AC-S172-11: removing selected skill returns it to dropdown', async () => {
    renderModal()

    fireEvent.change(await screen.findByLabelText('新增技能'), { target: { value: 'skill-a' } })
    fireEvent.click(screen.getByRole('button', { name: '新增' }))
    fireEvent.click(screen.getByRole('button', { name: '移除 deep-research' }))

    expect(screen.queryByTestId('selected-skills-list')).not.toHaveTextContent('deep-research')
    expect(screen.getByLabelText('新增技能')).toHaveTextContent('deep-research · Research · v1.0.0')
  })

  it('AC-S172-12: submit sends selected skill ids without UUID textarea', async () => {
    const { onClose } = renderModal()

    fireEvent.change(screen.getByLabelText(/名稱/), { target: { value: 'DevOps Starter Pack' } })
    fireEvent.change(screen.getByLabelText(/分類/), { target: { value: 'DevOps' } })
    fireEvent.change(await screen.findByLabelText('新增技能'), { target: { value: 'skill-a' } })
    fireEvent.click(screen.getByRole('button', { name: '新增' }))
    fireEvent.change(screen.getByLabelText('新增技能'), { target: { value: 'skill-b' } })
    fireEvent.click(screen.getByRole('button', { name: '新增' }))

    expect(screen.queryByLabelText(/技能 ID 清單/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '建立集合' }))

    await waitFor(() => expect(createCollectionMock).toHaveBeenCalledWith({
      name: 'DevOps Starter Pack',
      description: null,
      category: 'DevOps',
      skillIds: ['skill-a', 'skill-b'],
    }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('AC-S172-13: dialog surface uses viewport-bounded scroll classes', async () => {
    renderModal()

    const surface = await screen.findByTestId('create-collection-dialog-surface')
    expect(surface.className).toContain('max-h-[calc(100vh-2rem)]')
    expect(surface.className).toContain('overflow-y-auto')
  })
})
