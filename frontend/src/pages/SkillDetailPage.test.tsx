import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
          <Route path="/skills/:id/edit" element={<div data-testid="skill-edit-route">編輯技能頁</div>} />
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
    globalThis.fetch = vi.fn().mockResolvedValue({
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
    globalThis.fetch = vi.fn().mockResolvedValue({
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
    globalThis.fetch = vi.fn().mockResolvedValue({
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

  // S153: 400 (格式錯誤 ID) 與 403 (ACL 拒讀) 對 user 都是「找不到此技能」
  // 不再顯示誤導性的 retry 提示。404 行為由上方既有 AC-1 覆蓋。
  it('S153 AC-1: 400 VALIDATION_ERROR (格式錯誤) shows 找不到此技能 (no retry hint)', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: () =>
        Promise.resolve({ error: 'VALIDATION_ERROR', message: "Invalid format for parameter 'id'" }),
    } as Response)
    renderPage('non-existent-skill-id-12345')
    await waitFor(() => {
      expect(screen.getByText('找不到此技能')).toBeInTheDocument()
    })
    expect(screen.queryByText('請稍後重試或重新整理頁面')).not.toBeInTheDocument()
  })

  it('S153 AC-2: 403 Access Denied shows 找不到此技能 (no retry hint)', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: () =>
        Promise.resolve({ status: 403, error: 'Forbidden', message: 'Access Denied' }),
    } as Response)
    renderPage('00000000-0000-0000-0000-000000000000')
    await waitFor(() => {
      expect(screen.getByText('找不到此技能')).toBeInTheDocument()
    })
    expect(screen.queryByText('請稍後重試或重新整理頁面')).not.toBeInTheDocument()
  })

  it('S174 AC-S174-3: 401 Unauthorized shows 找不到此技能 (no retry hint)', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () =>
        Promise.resolve({ error: 'UNAUTHORIZED', message: 'Authentication required' }),
    } as Response)
    renderPage('00000000-0000-0000-0000-000000000000')
    await waitFor(() => {
      expect(screen.getByText('找不到此技能')).toBeInTheDocument()
    })
    expect(screen.queryByText('請稍後重試或重新整理頁面')).not.toBeInTheDocument()

    const link = screen.getByText('返回列表')
    expect(link.closest('a')).toHaveAttribute('href', '/browse')
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
  // S142b fields
  verified: false,
  latestVersionPublishedAt: null,
  license: null,
  compatibility: [],
  versionCount: 0,
  openFlagCount: 0,
  ownerId: 'alice',
  averageRating: 0,
  reviewCount: 0,
  viewerPermissions: {
    isOwner: true,
    canView: true,
    canDownload: status === 'PUBLISHED',
    canEdit: true,
    canDelete: true,
    canShare: true,
    canManageGrants: true,
  },
})

const SKILL_SUB_PATHS = ['/versions', '/stats', '/bundles', '/files', '/scores', '/security-report', '/grants', '/flags', '/reviews']
function isSkillSubPath(u: string): boolean {
  return SKILL_SUB_PATHS.some(p => u.includes(p))
}

/** Route-aware fetch mock: skill endpoint returns fixture; other APIs return safe defaults */
function mockFetchForSkill(skill: ReturnType<typeof skillFixture>) {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => {
    const u = typeof url === 'string' ? url : String(url)
    if (u.includes(`/skills/${skill.id}`) && !isSkillSubPath(u)) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(skill) } as Response)
    }
    if (u.includes('/versions')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
    }
    if (u.includes('/stats')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(null) } as Response)
    }
    if (u.includes('/subscriptions')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
    }
    // Default: 404 (handles /scores, /security-report, /files, /me, etc.)
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

describe('SkillDetailPage — S172 responsive detail body', () => {
  it('AC-S172-1: detail body uses responsive grid without fixed 232px sidebar on mobile', async () => {
    const skill = skillFixture('PUBLISHED', 'skill-responsive-1')
    mockFetchForSkill(skill)
    renderPage('skill-responsive-1')

    await waitFor(() => {
      expect(screen.getByTestId('download-cta')).toBeInTheDocument()
    })

    const body = screen.getByTestId('skill-detail-body')
    expect(body.className).toContain('grid-cols-1')
    expect(body.className).toContain('lg:grid-cols-[minmax(0,1fr)_232px]')
    expect(body).not.toHaveStyle({ gridTemplateColumns: '1fr 232px' })

    const main = screen.getByTestId('skill-detail-main')
    expect(main.className).toContain('min-w-0')
  })

  it('AC-S172-2: sidebar switches from left divider to stacked divider below lg', async () => {
    const skill = skillFixture('PUBLISHED', 'skill-responsive-2')
    mockFetchForSkill(skill)
    renderPage('skill-responsive-2')

    await waitFor(() => {
      expect(screen.getByTestId('download-cta')).toBeInTheDocument()
    })

    const sidebar = screen.getByTestId('sidebar')
    expect(sidebar.className).toContain('border-t')
    expect(sidebar.className).toContain('pt-6')
    expect(sidebar.className).toContain('lg:border-l')
    expect(sidebar.className).toContain('lg:pl-[22px]')
    expect(sidebar).not.toHaveStyle({ borderLeft: '0.5px solid var(--line, rgba(255,255,255,0.08))' })
  })
})

describe('SkillDetailPage — S187 edit route and version history', () => {
  it('AC-S187-1: 詳情頁編輯按鈕導向 edit page', async () => {
    const skill = skillFixture('PUBLISHED', 'skill-s187-edit-route')
    mockFetchForSkill(skill)
    renderPage(skill.id)

    await waitFor(() => {
      expect(screen.getByTestId('edit-skill-btn')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByTestId('edit-skill-btn'))

    await waitFor(() => {
      expect(screen.getByTestId('skill-edit-route')).toBeInTheDocument()
    })
    expect(screen.queryByRole('dialog', { name: '編輯技能' })).not.toBeInTheDocument()
  })

  it('AC-S187-2: 版本頁籤只顯示 Version History', async () => {
    const skill = skillFixture('PUBLISHED', 'skill-s188-add-version')
    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const u = typeof url === 'string' ? url : String(url)
      if (u.endsWith('/me')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ sub: 'alice', userId: 'alice', roles: [], groups: [] }),
        } as Response)
      }
      if (u === `/api/v1/skills/${skill.id}/versions` && init?.method === 'PUT') {
        throw new Error('S187 detail versions tab must not submit a new version')
      }
      if (u.includes(`/skills/${skill.id}`) && !isSkillSubPath(u)) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(skill) } as Response)
      }
      if (u.includes('/versions')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
      }
      if (u.includes('/stats')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(null) } as Response)
      }
      if (u.includes('/subscriptions')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
      }
      return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as Response)
    })

    renderPage(skill.id)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: '版本' })).toBeInTheDocument()
    })
    const versionsTab = screen.getByRole('tab', { name: '版本' })
    fireEvent.pointerDown(versionsTab, { button: 0, ctrlKey: false })
    fireEvent.click(versionsTab)
    fireEvent.keyDown(versionsTab, { key: 'Enter', code: 'Enter' })

    await waitFor(() => {
      expect(screen.getByText('尚無版本記錄')).toBeInTheDocument()
    })
    expect(screen.queryByText('新增版本')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('版本號')).not.toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).toBeNull()
    expect(screen.queryByRole('button', { name: '新增' })).not.toBeInTheDocument()
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
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      const u = typeof url === 'string' ? url : String(url)
      if (u.endsWith('/me')) {
        return Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve({ sub: meSub, roles: [], groups: [], companyId: null, deptId: null, scope: '' }),
        } as Response)
      }
      if (u.includes(`/skills/${skill.id}`) && !isSkillSubPath(u)) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(skill) } as Response)
      }
      if (u.includes('/versions')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response)
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
    const skill = {
      ...skillFixture('PUBLISHED', 'skill-share-2'),
      ownerId: 'alice',
      viewerPermissions: {
        isOwner: false,
        canView: true,
        canDownload: true,
        canEdit: false,
        canDelete: false,
        canShare: false,
        canManageGrants: false,
      },
    }
    mockFetchWithOwnerAndMe(skill, 'bob')
    renderPage('skill-share-2')
    await waitFor(() => {
      // wait for skill to load (download CTA present means load succeeded)
      expect(screen.getByTestId('download-cta')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '分享' })).not.toBeInTheDocument()
  })

  it('S169 AC-14: viewerPermissions.canShare=true controls 分享 button, not ownerId === me.sub', async () => {
    const skill = {
      ...skillFixture('PUBLISHED', 'skill-share-vp'),
      ownerId: 'u_alice0',
      viewerPermissions: {
        isOwner: true,
        canView: true,
        canDownload: true,
        canEdit: true,
        canDelete: true,
        canShare: true,
        canManageGrants: true,
      },
    }
    mockFetchWithOwnerAndMe(skill, 'oauth-sub-not-owner-id')
    renderPage('skill-share-vp')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument()
    })
  })
})
