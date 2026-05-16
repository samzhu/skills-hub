/**
 * S154b T04 — ShareModal 4 polish tests。
 *
 * 涵蓋 AC-6（list enriched displayName）/ AC-S184-9（public 不再是分享目標）/
 * AC-S184-11（public grant row 不顯示在 ShareModal）/ AC-9（placeholder
 * 改 email/handle 友善版）。
 *
 * Mock 策略：vi.mock 替換 `useGrants/useCreateGrant/useRevokeGrant` 3 個 hooks，
 * 不走真實 fetch；測試聚焦於 UI render 行為，不驗 backend resolve（後者由
 * SkillGrantServiceTest 走 JVM test 覆蓋 AC-9 backend path）。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as useGrantsModule from '../hooks/useGrants'
import { ShareModal } from './ShareModal'
import type { SkillGrant } from '@/api/grants'
// sonner toast is invoked by handlers — mock as no-op so tests don't need DOM portal
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('../hooks/useGrants')

const mockedUseGrants = vi.mocked(useGrantsModule.useGrants)
const mockedUseCreateGrant = vi.mocked(useGrantsModule.useCreateGrant)
const mockedUseRevokeGrant = vi.mocked(useGrantsModule.useRevokeGrant)

function setupMocks(grants: SkillGrant[]) {
  mockedUseGrants.mockReturnValue({
    data: grants,
    isLoading: false,
  } as any)
  mockedUseCreateGrant.mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  } as any)
  mockedUseRevokeGrant.mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as any)
}

const renderModal = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ShareModal skillId="sk_1" onClose={vi.fn()} />
    </QueryClientProvider>,
  )
}

describe('ShareModal — S154b T04 4 polish', () => {
  it('AC-6: enriched grant entry 顯 displayName 而非 raw user_id', () => {
    setupMocks([
      {
        id: 'g1',
        principalType: 'user',
        principalId: 'u_a3f9c1',
        role: 'OWNER',
        grantedBy: 'u_a3f9c1',
        grantedAt: '2026-05-01T00:00:00Z',
        // S154b backend enrich fields
        displayName: 'Alice Chen',
        handle: 'alice',
      } as SkillGrant,
    ])
    renderModal()
    expect(screen.getByText('Alice Chen')).toBeInTheDocument()
    // raw user_id 不該出現作為主要顯示
    expect(screen.queryByText(/u_a3f9c1/)).toBeNull()
  })

  it('AC-S184-9: 新增分享 radio 支援 user / group / company，不顯示 public', () => {
    setupMocks([])
    renderModal()
    expect(screen.getByRole('radio', { name: /user/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /group/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /company/i })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /public/i })).toBeNull()
  })

  it('S169 AC-13: role picker only exposes 可檢視 / 可編輯, not raw read/write/delete', () => {
    setupMocks([])
    renderModal()
    expect(screen.getByRole('radio', { name: '可檢視' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '可編輯' })).toBeInTheDocument()
    expect(screen.queryByText('read')).toBeNull()
    expect(screen.queryByText('write')).toBeNull()
    expect(screen.queryByText('delete')).toBeNull()
  })

  it('AC-S184-11: public grant row 是 visibility mirror，不顯示在現有分享清單', () => {
    setupMocks([
      {
        id: 'g_public',
        principalType: 'public',
        principalId: '*',
        role: 'VIEWER',
        grantedBy: 'u_a3f9c1',
        grantedAt: '2026-05-01T00:00:00Z',
      } as SkillGrant,
    ])
    renderModal()
    expect(screen.getByText('尚無分享設定')).toBeInTheDocument()
    expect(screen.queryByText(/public/)).toBeNull()
  })

  it('AC-9: placeholder 改「輸入使用者 email 或 handle」', () => {
    setupMocks([])
    renderModal()
    // user radio 為 default → input placeholder 改友善版
    expect(screen.getByPlaceholderText('輸入使用者 email 或 handle')).toBeInTheDocument()
  })
})
