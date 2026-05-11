import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { PageHeader } from './PageHeader'
import type { Skill } from '@/types/skill'
import * as subscriptionHooks from '@/hooks/useSubscription'
import * as useAuthModule from '@/hooks/useAuth'
import * as grantsApi from '@/api/grants'

vi.mock('@/hooks/useSubscription')
vi.mock('@/hooks/useAuth')
vi.mock('@/api/grants', async () => {
  const actual = await vi.importActual<typeof import('@/api/grants')>('@/api/grants')
  return { ...actual, fetchGrants: vi.fn().mockResolvedValue([]) }
})
void grantsApi // ensure import not tree-shaken

const mockSubscription = vi.mocked(subscriptionHooks)
const mockUseAuth = vi.mocked(useAuthModule.useAuth)

const baseSkill: Skill = {
  id: 's1', name: 'My Skill', description: 'A test skill', author: 'alice',
  category: 'AI', latestVersion: '1.0.0', riskLevel: 'LOW',
  status: 'PUBLISHED', downloadCount: 100, averageRating: 4.5, reviewCount: 10,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-05-04T00:00:00Z',
  verified: true, latestVersionPublishedAt: '2026-05-04T00:00:00Z',
  license: 'MIT', compatibility: [], versionCount: 3, openFlagCount: 0,
}

function setupMocks(subscribed = false) {
  mockUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { sub: 'bob', email: 'bob@x.com', name: 'Bob' },
    login: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof useAuthModule.useAuth>)
  mockSubscription.useIsSubscribed.mockReturnValue(subscribed)
  mockSubscription.useSubscribeSkill.mockReturnValue({ mutate: vi.fn() } as any)
  mockSubscription.useUnsubscribeSkill.mockReturnValue({ mutate: vi.fn() } as any)
}

const renderHeader = (skill = baseSkill, isOwner = false) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PageHeader
          skill={skill}
          isOwner={isOwner}
          activeTab="overview"
          onTabChange={vi.fn()}
          scores={null}
          report={null}
          stats={[]}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PageHeader', () => {
  it('AC-S142a-1: verified=true → shows verified-pill', () => {
    setupMocks()
    renderHeader()
    expect(screen.getByTestId('verified-pill')).toBeTruthy()
    expect(screen.getByText('已驗證')).toBeTruthy()
  })

  it('AC-S142a-2: verified=false → no verified-pill', () => {
    setupMocks()
    renderHeader({ ...baseSkill, verified: false })
    expect(screen.queryByTestId('verified-pill')).toBeNull()
  })

  it('AC-S142a-3: isOwner=true → no Star button', () => {
    setupMocks()
    renderHeader(baseSkill, true)
    expect(screen.queryByTestId('star-btn')).toBeNull()
  })

  it('AC-S142a-3: star click unsubscribed → subscribe.mutate called', () => {
    setupMocks(false)
    const subscribeMutate = vi.fn()
    mockSubscription.useSubscribeSkill.mockReturnValue({ mutate: subscribeMutate } as any)
    renderHeader()
    fireEvent.click(screen.getByTestId('star-btn'))
    expect(subscribeMutate).toHaveBeenCalledWith('s1')
  })

  it('AC-S142a-3: star click subscribed → unsubscribe.mutate called', () => {
    setupMocks(true)
    const unsubscribeMutate = vi.fn()
    mockSubscription.useUnsubscribeSkill.mockReturnValue({ mutate: unsubscribeMutate } as any)
    renderHeader()
    fireEvent.click(screen.getByTestId('star-btn'))
    expect(unsubscribeMutate).toHaveBeenCalledWith('s1')
  })

  it('shows skill name and description', () => {
    setupMocks()
    renderHeader()
    expect(screen.getByText('My Skill')).toBeTruthy()
    expect(screen.getByText('A test skill')).toBeTruthy()
  })

  it('SUSPENDED skill → no download CTA', () => {
    setupMocks()
    renderHeader({ ...baseSkill, status: 'SUSPENDED' })
    expect(screen.queryByTestId('download-cta')).toBeNull()
  })

  it('AC-4 (S154b): skill.authorEmail 不存在 → 無「聯絡作者」mailto link', () => {
    setupMocks()
    // baseSkill 預設無 authorEmail field
    renderHeader()
    expect(screen.queryByRole('link', { name: '聯絡作者' })).toBeNull()
  })

  it('AC-5 (S154b): skill.authorEmail 存在 → mailto:{email} link 渲染', () => {
    setupMocks()
    renderHeader({ ...baseSkill, authorEmail: 'alice@example.com' })
    const link = screen.getByRole('link', { name: '聯絡作者' })
    expect(link).toBeTruthy()
    expect(link.getAttribute('href')).toBe('mailto:alice@example.com')
  })

  it('S163b: 非 owner → 不顯 [編輯] button', () => {
    setupMocks()
    renderHeader(baseSkill, false)
    expect(screen.queryByTestId('edit-skill-btn')).toBeNull()
  })

  it('S163b: owner + onEditClick 有傳 → 顯 [編輯] button + click 觸發 callback', () => {
    setupMocks()
    const onEditClick = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <PageHeader
            skill={baseSkill}
            isOwner={true}
            activeTab="overview"
            onTabChange={vi.fn()}
            scores={null}
            report={null}
            stats={[]}
            onEditClick={onEditClick}
          />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const btn = screen.getByTestId('edit-skill-btn')
    expect(btn.textContent).toBe('編輯')
    fireEvent.click(btn)
    expect(onEditClick).toHaveBeenCalledTimes(1)
  })

  it('S163b: owner 但 onEditClick 未傳 → 不顯 [編輯] button（防漏接 parent prop）', () => {
    setupMocks()
    renderHeader(baseSkill, true)
    expect(screen.queryByTestId('edit-skill-btn')).toBeNull()
  })

  it('regression (S142a-T06 prod-bug): download-cta click invokes onDownload prop', () => {
    setupMocks()
    const onDownload = vi.fn()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <PageHeader
            skill={baseSkill}
            isOwner={false}
            activeTab="skill-md"
            onTabChange={vi.fn()}
            scores={null}
            report={null}
            stats={[]}
            onDownload={onDownload}
          />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    fireEvent.click(screen.getByTestId('download-cta'))
    // 守 onClick wiring：S142a-T06 ship 時這個 button 接 onClick={onDownload} 但 parent (SkillDetailPage)
    // 漏傳 onDownload prop → click 沒反應 (real prod bug). 本 regression 驗 PageHeader 端 wire 仍正確；
    // SkillDetailPage 端 prop 傳遞由 e2e (S140-critical-path-download) 守。
    expect(onDownload).toHaveBeenCalledTimes(1)
  })
})
