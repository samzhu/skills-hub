/**
 * S154b T04 — ShareModal 4 polish tests。
 *
 * 涵蓋 AC-6（list enriched displayName）/ AC-7（只有 user/public radio）/
 * AC-8（已 public → public radio disabled + tooltip）/ AC-9（placeholder
 * 改 email/handle 友善版）。
 *
 * Mock 策略：vi.mock 替換 `useGrants/useCreateGrant/useRevokeGrant` 3 個 hooks，
 * 不走真實 fetch；測試聚焦於 UI render 行為，不驗 backend resolve（後者由
 * SkillGrantServiceTest 走 JVM test 覆蓋 AC-9 backend path）。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
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
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
  mockedUseCreateGrant.mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
  mockedUseRevokeGrant.mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
}

const renderModal = () => render(<ShareModal skillId="sk_1" onClose={vi.fn()} />)

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

  it('AC-7: 新增分享 radio 只有 user / public，無 group / company', () => {
    setupMocks([])
    renderModal()
    // user / public 仍在
    expect(screen.getByRole('radio', { name: /user/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /public/i })).toBeInTheDocument()
    // group / company 移除
    expect(screen.queryByRole('radio', { name: /group/i })).toBeNull()
    expect(screen.queryByRole('radio', { name: /company/i })).toBeNull()
  })

  it('AC-8: 已 public:*:read 時 public radio disabled + tooltip', () => {
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
    const publicRadio = screen.getByRole('radio', { name: /public/i })
    expect(publicRadio).toBeDisabled()
    // tooltip / aria-label / inline note 任一形式皆可，這裡驗 inline text 出現
    expect(screen.getByText(/此技能已公開/)).toBeInTheDocument()
  })

  it('AC-9: placeholder 改「輸入使用者 email 或 handle」', () => {
    setupMocks([])
    renderModal()
    // user radio 為 default → input placeholder 改友善版
    expect(screen.getByPlaceholderText('輸入使用者 email 或 handle')).toBeInTheDocument()
  })
})
