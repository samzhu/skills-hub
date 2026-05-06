import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SkillDetailPage } from './SkillDetailPage'

vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof import('sonner')>()
  return { ...actual, toast: { success: vi.fn(), error: vi.fn() } }
})

/**
 * SkillDetailPage error path tests — 對齊 docs/grimo/test-cases.md Round 1.4
 * negative case + S039 區分 404 not-found 與其他 server / network error。
 */

const renderPage = (id = 'non-existent-uuid') => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/skills/${id}`]}>
        <Routes>
          <Route path="/skills/:id" element={<SkillDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SkillDetailPage — error paths (ledger Round 1.4)', () => {
  it('AC-1: 404 not-found shows specific 找不到此技能 message', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({ error: 'NotFound', message: 'Skill not found' }),
    } as Response)
    renderPage('non-existent-uuid')
    await waitFor(() => {
      expect(screen.getByText('找不到此技能')).toBeInTheDocument()
    })
    // 404 不顯 retry hint（per S039 區分）
    expect(screen.queryByText('請稍後重試或重新整理頁面')).not.toBeInTheDocument()
  })

  it('AC-2: 500 server error shows generic error + retry hint', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: 'Internal', message: 'boom' }),
    } as Response)
    renderPage('any-id')
    await waitFor(() => {
      expect(screen.getByText('載入技能時發生錯誤')).toBeInTheDocument()
    })
    expect(screen.getByText('請稍後重試或重新整理頁面')).toBeInTheDocument()
  })

  it('AC-3 (S102): error state shows 返回列表 link to /browse', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({}),
    } as Response)
    renderPage('xxx')
    await waitFor(() => {
      const link = screen.getByText('返回列表')
      expect(link.closest('a')).toHaveAttribute('href', '/browse')
    })
  })
})

const skillFixture = (status: string, id = 'skill-test-1') => ({
  id,
  name: 'Test Skill',
  description: 'desc',
  author: 'alice',
  category: 'Testing',
  status,
  latestVersion: status === 'PUBLISHED' ? '1.0.0' : null,
  downloadCount: 0,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  aclEntries: [],
  riskLevel: null,
  iconEmoji: null,
  bundleCount: 0,
})

/** Route-aware fetch mock: skill endpoint returns fixture; other APIs return safe defaults */
function mockFetchForSkill(skill: ReturnType<typeof skillFixture>) {
  global.fetch = vi.fn().mockImplementation((url: string) => {
    const u = typeof url === 'string' ? url : String(url)
    if (u.includes(`/skills/${skill.id}`) && !u.includes('/versions') && !u.includes('/stats') && !u.includes('/bundles')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(skill) } as Response)
    }
    if (u.includes('/versions')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve({ content: [] }) } as Response)
    }
    if (u.includes('/stats')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(null) } as Response)
    }
    if (u.includes('/subscriptions')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
    }
    // Default: 404
    return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as Response)
  })
}

describe('SkillDetailPage — S133 MarkdownActionMenu visibility', () => {
  it('AC-5: PUBLISHED skill with latestVersion shows Markdown trigger', async () => {
    const skill = skillFixture('PUBLISHED', 'skill-pub-1')
    mockFetchForSkill(skill)
    renderPage('skill-pub-1')
    await waitFor(() => {
      expect(screen.getByLabelText('Markdown 操作')).toBeInTheDocument()
    })
  })

  it('AC-6: DRAFT skill does not show Markdown trigger', async () => {
    const skill = skillFixture('DRAFT', 'skill-draft-1')
    mockFetchForSkill(skill)
    renderPage('skill-draft-1')
    await waitFor(() => {
      // skill data loads successfully but no Markdown trigger because status != PUBLISHED
      expect(screen.queryByLabelText('Markdown 操作')).not.toBeInTheDocument()
    })
  })
})

/**
 * S114a AC-11 — 分享按鈕 owner-only visibility。
 *
 * `me` 回應的 `sub` 欄位與 `skill.ownerId` 比對；只有 owner 顯示「分享」按鈕。
 */
describe('SkillDetailPage — S114a AC-11 share button visibility', () => {
  function mockFetchWithOwnerAndMe(
    skill: ReturnType<typeof skillFixture>,
    meSub: string,
  ) {
    global.fetch = vi.fn().mockImplementation((url: string) => {
      const u = typeof url === 'string' ? url : String(url)
      if (u.endsWith('/me')) {
        return Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve({ sub: meSub, roles: [], groups: [], companyId: null, deptId: null, scope: '' }),
        } as Response)
      }
      if (
        u.includes(`/skills/${skill.id}`) &&
        !u.includes('/versions') &&
        !u.includes('/stats') &&
        !u.includes('/bundles') &&
        !u.includes('/grants')
      ) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(skill) } as Response)
      }
      if (u.includes('/versions')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ content: [] }) } as Response)
      }
      if (u.includes('/stats')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(null) } as Response)
      }
      if (u.includes('/subscriptions')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
      }
      return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as Response)
    })
  }

  it('AC-11: owner sees 分享 button', async () => {
    const skill = { ...skillFixture('PUBLISHED', 'skill-share-1'), ownerId: 'alice' }
    mockFetchWithOwnerAndMe(skill, 'alice')
    renderPage('skill-share-1')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument()
    })
  })

  it('AC-11: non-owner does not see 分享 button', async () => {
    const skill = { ...skillFixture('PUBLISHED', 'skill-share-2'), ownerId: 'alice' }
    mockFetchWithOwnerAndMe(skill, 'bob')
    renderPage('skill-share-2')
    await waitFor(() => {
      // wait for skill to load (Download button present means load succeeded)
      expect(screen.getByText('下載')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '分享' })).not.toBeInTheDocument()
  })
})
