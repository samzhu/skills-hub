import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { PageHeader } from './PageHeader'
import type { Skill } from '@/types/skill'
import * as subscriptionHooks from '@/hooks/useSubscription'
import * as useAuthModule from '@/hooks/useAuth'

vi.mock('@/hooks/useSubscription')
vi.mock('@/hooks/useAuth')

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
  } as ReturnType<typeof useAuthModule.useAuth>)
  mockSubscription.useIsSubscribed.mockReturnValue(subscribed)
  mockSubscription.useSubscribeSkill.mockReturnValue({ mutate: vi.fn() } as any)
  mockSubscription.useUnsubscribeSkill.mockReturnValue({ mutate: vi.fn() } as any)
}

const renderHeader = (skill = baseSkill, isOwner = false) =>
  render(
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
    </MemoryRouter>,
  )

describe('PageHeader', () => {
  it('AC-S142a-1: verified=true → shows verified-pill', () => {
    setupMocks()
    renderHeader()
    expect(screen.getByTestId('verified-pill')).toBeTruthy()
    expect(screen.getByText('Verified')).toBeTruthy()
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
})
