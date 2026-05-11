import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { VisibilityToggleButton } from './VisibilityToggleButton'
import * as grantsApi from '@/api/grants'
import type { SkillGrant } from '@/api/grants'

vi.mock('@/api/grants', async () => {
  const actual = await vi.importActual<typeof import('@/api/grants')>('@/api/grants')
  return {
    ...actual,
    fetchGrants: vi.fn(),
    createGrant: vi.fn(),
    revokeGrant: vi.fn(),
  }
})

const fetchGrants = vi.mocked(grantsApi.fetchGrants)
const createGrant = vi.mocked(grantsApi.createGrant)
const revokeGrant = vi.mocked(grantsApi.revokeGrant)

function publicGrant(id = 'g-pub'): SkillGrant {
  return {
    id,
    principalType: 'public',
    principalId: '*',
    role: 'VIEWER',
    grantedBy: 'alice',
    grantedAt: '2026-05-01T00:00:00Z',
  }
}

function ownerGrant(): SkillGrant {
  return {
    id: 'g-owner',
    principalType: 'user',
    principalId: 'alice',
    role: 'OWNER',
    grantedBy: 'alice',
    grantedAt: '2026-05-01T00:00:00Z',
  }
}

function renderToggle(skillId = 's1') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <VisibilityToggleButton skillId={skillId} />
    </QueryClientProvider>,
  )
}

describe('VisibilityToggleButton', () => {
  beforeEach(() => {
    fetchGrants.mockReset()
    createGrant.mockReset().mockResolvedValue({ grantId: 'new-pub' })
    revokeGrant.mockReset().mockResolvedValue(undefined)
  })

  it('S163 AC-4: 含 public:* grant → 顯「轉為私人」', async () => {
    fetchGrants.mockResolvedValue([ownerGrant(), publicGrant('pg-1')])
    renderToggle()
    const btn = await screen.findByRole('button', { name: /轉為私人|公開分享/ })
    expect(btn.textContent).toBe('轉為私人')
  })

  it('S163 AC-6: 無 public:* grant → 顯「公開分享」', async () => {
    fetchGrants.mockResolvedValue([ownerGrant()])
    renderToggle()
    const btn = await screen.findByRole('button', { name: /轉為私人|公開分享/ })
    expect(btn.textContent).toBe('公開分享')
  })

  it('S163 AC-4: 點「轉為私人」→ revokeGrant 帶該 publicGrant.id', async () => {
    fetchGrants.mockResolvedValue([publicGrant('pg-42')])
    renderToggle()
    const btn = await screen.findByRole('button', { name: /轉為私人|公開分享/ })
    fireEvent.click(btn)
    await waitFor(() => expect(revokeGrant).toHaveBeenCalledTimes(1))
    expect(revokeGrant).toHaveBeenCalledWith('s1', 'pg-42')
    expect(createGrant).not.toHaveBeenCalled()
  })

  it('S163 AC-6: 點「公開分享」→ createGrant({public, *, VIEWER})', async () => {
    fetchGrants.mockResolvedValue([])
    renderToggle()
    const btn = await screen.findByRole('button', { name: /轉為私人|公開分享/ })
    fireEvent.click(btn)
    await waitFor(() => expect(createGrant).toHaveBeenCalledTimes(1))
    expect(createGrant).toHaveBeenCalledWith('s1', {
      principalType: 'public',
      principalId: '*',
      role: 'VIEWER',
    })
    expect(revokeGrant).not.toHaveBeenCalled()
  })

  it('loading 期間 → 顯 disabled placeholder（無法 click）', () => {
    fetchGrants.mockReturnValue(new Promise(() => {})) // never resolve
    renderToggle()
    const btn = screen.getByTestId('visibility-toggle')
    expect((btn as HTMLButtonElement).disabled).toBe(true)
  })
})
