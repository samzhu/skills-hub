import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReviewsPanel } from './ReviewsPanel'
import * as useAuthModule from '../hooks/useAuth'
import type { Skill } from '@/types/skill'

vi.mock('../hooks/useAuth')
const mockUseAuth = vi.mocked(useAuthModule.useAuth)

/**
 * S098e2-T04 — ReviewsPanel isolation tests（per S112-T03 啟示直接 unit test
 * component 而非 page-level Tabs interaction）。
 *
 * Mock fetch dispatch by URL：
 * - GET /api/v1/skills/{id}/reviews → reviews array
 * - POST /api/v1/skills/{id}/reviews → 201 + {id}
 */

const SKILL_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const skillFixture: Skill = {
  id: SKILL_ID,
  name: 'sample-skill',
  description: '測試技能',
  author: 'alice',
  category: 'DevOps',
  latestVersion: '1.0.0',
  riskLevel: 'LOW',
  status: 'PUBLISHED',
  visibility: 'PUBLIC',
  downloadCount: 42,
  averageRating: 4.0,
  reviewCount: 3,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  verified: true,
  latestVersionPublishedAt: '2026-01-01T00:00:00Z',
  license: null,
  compatibility: [],
  versionCount: 1,
  openFlagCount: 0,
}

const renderPanel = (skill: Skill = skillFixture, currentUserId = 'alice') => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <ReviewsPanel skill={skill} currentUserId={currentUserId} />
    </QueryClientProvider>,
  )
}

function mockFetchByUrl(reviewsResponse: unknown[], authed = true) {
  ;(globalThis as any).fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    if (url.includes('/api/v1/me')) {
      if (authed) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ sub: 'alice', email: 'alice@example.com' }) } as Response)
      }
      return Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({}) } as Response)
    }
    if (url.includes(`/api/v1/skills/${SKILL_ID}/reviews`)) {
      if (init?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 201, json: () => Promise.resolve({ id: 'new-review-id' }) } as Response)
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(reviewsResponse) } as Response)
    }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) } as Response)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  // AuthGatedButton 內含 useAuth；預設 mock 為 authenticated 讓評論 CTA 正常觸發
  mockUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { sub: 'alice', email: 'alice@example.com' },
    login: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof useAuthModule.useAuth>)
})

describe('ReviewsPanel (S098e2-T04)', () => {
  it('S098e2 AC-10: 0 reviews 顯示 invite EmptyState + 撰寫評論 CTA', async () => {
    mockFetchByUrl([])
    const skill = { ...skillFixture, averageRating: 0, reviewCount: 0 }
    renderPanel(skill)
    await waitFor(() => {
      expect(screen.getByText('成為第一個評論這個技能的人')).toBeInTheDocument()
    })
    // 撰寫評論 CTA 在 EmptyState primaryAction 內
    expect(screen.getByRole('button', { name: '撰寫評論' })).toBeInTheDocument()
  })

  it('S098e2 AC-11: >0 reviews 顯示 RatingHero + list', async () => {
    mockFetchByUrl([
      {
        id: 'r1', skillId: SKILL_ID, authorId: 'u1', rating: 5, content: '極優',
        createdAt: '2026-05-03T03:00:00Z', updatedAt: '2026-05-03T03:00:00Z',
      },
      {
        id: 'r2', skillId: SKILL_ID, authorId: 'u2', rating: 4, content: '很好',
        createdAt: '2026-05-02T12:00:00Z', updatedAt: '2026-05-02T12:00:00Z',
      },
      {
        id: 'r3', skillId: SKILL_ID, authorId: 'u3', rating: 3, content: '尚可',
        createdAt: '2026-05-01T00:00:00Z', updatedAt: '2026-05-01T00:00:00Z',
      },
    ])
    renderPanel(skillFixture, 'someone-else') // 非 author，不應顯刪除
    await waitFor(() => {
      expect(screen.getByText('極優')).toBeInTheDocument()
    })
    // RatingHero「4.0 · 3 則評論」
    expect(screen.getByText('4.0')).toBeInTheDocument()
    expect(screen.getByText('3 則評論')).toBeInTheDocument()
    // 3 row content 全顯
    expect(screen.getByText('很好')).toBeInTheDocument()
    expect(screen.getByText('尚可')).toBeInTheDocument()
    // EmptyState 不應出現
    expect(screen.queryByText('成為第一個評論這個技能的人')).not.toBeInTheDocument()
    // 沒有任何 review 是「我的」 → 應顯撰寫 CTA
    expect(screen.getByRole('button', { name: '撰寫評論' })).toBeInTheDocument()
  })

  it('AC-S192-5: review row 顯示 authorDisplayName，刪除判斷仍使用 authorId', async () => {
    mockFetchByUrl([
      {
        id: 'r-mine', skillId: SKILL_ID, authorId: 'u_f7eb3a', authorDisplayName: 'Sam Zhu',
        authorHandle: null, rating: 5, content: '我寫的',
        createdAt: '2026-05-03T03:00:00Z', updatedAt: '2026-05-03T03:00:00Z',
      },
    ])
    renderPanel(skillFixture, 'u_f7eb3a')
    await waitFor(() => expect(screen.getByText('我寫的')).toBeInTheDocument())

    expect(screen.getByText('Sam Zhu')).toBeInTheDocument()
    expect(screen.queryByText('u_f7eb3a')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刪除我的評論' })).toBeInTheDocument()
  })

  it('S098e2 AC-11: my own review row 顯示刪除按鈕', async () => {
    mockFetchByUrl([
      {
        id: 'r-mine', skillId: SKILL_ID, authorId: 'alice', rating: 5, content: '我寫的',
        createdAt: '2026-05-03T03:00:00Z', updatedAt: '2026-05-03T03:00:00Z',
      },
    ])
    renderPanel(skillFixture, 'alice')
    await waitFor(() => {
      expect(screen.getByText('我寫的')).toBeInTheDocument()
    })
    // 我的 review 加刪除 affordance
    expect(screen.getByRole('button', { name: '刪除我的評論' })).toBeInTheDocument()
    // 已寫過評論 → 不應再顯撰寫 CTA
    expect(screen.queryByRole('button', { name: '撰寫評論' })).not.toBeInTheDocument()
  })

  it('S098e2 AC-12: 點撰寫評論開 modal → 選星 → 輸入 → Submit 觸發 POST', async () => {
    mockFetchByUrl([])
    const skill = { ...skillFixture, averageRating: 0, reviewCount: 0 }
    renderPanel(skill)
    // 等 EmptyState 出現
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '撰寫評論' })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: '撰寫評論' }))

    // Modal 開
    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: '撰寫評論' })).toBeInTheDocument()
    })

    // 選 5 星（aria-label="5 星"）
    fireEvent.click(screen.getByRole('radio', { name: '5 星' }))
    // 輸入內容
    const textarea = screen.getByLabelText('內容')
    fireEvent.change(textarea, { target: { value: 'Great skill' } })
    // Submit
    fireEvent.click(screen.getByRole('button', { name: '送出' }))

    // POST request 應觸發 — 驗 fetch mock 收到 POST
    await waitFor(() => {
      const calls = ((globalThis as any).fetch as ReturnType<typeof vi.fn>).mock.calls
      const postCall = calls.find((c) => c[1]?.method === 'POST')
      expect(postCall).toBeDefined()
      expect(postCall![1].body).toContain('Great skill')
      expect(postCall![1].body).toContain('"rating":5')
    })
  })
})
