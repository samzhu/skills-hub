import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { VisibilityToggleButton } from './VisibilityToggleButton'
import * as skillsApi from '@/api/skills'
import { skillKeys } from '@/api/queryKeys'
import type { Skill } from '@/types/skill'

vi.mock('@/api/skills', async () => {
  const actual = await vi.importActual<typeof import('@/api/skills')>('@/api/skills')
  return {
    ...actual,
    setSkillVisibility: vi.fn(),
  }
})

const setSkillVisibility = vi.mocked(skillsApi.setSkillVisibility)

function renderToggle(skillId = 's1', visibility: 'PUBLIC' | 'PRIVATE' = 'PUBLIC') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(skillKeys.detail(skillId), {
    id: skillId,
    visibility,
    updatedAt: '2026-05-01T00:00:00Z',
  } as Skill)
  render(
    <QueryClientProvider client={client}>
      <VisibilityToggleButton skillId={skillId} visibility={visibility} />
    </QueryClientProvider>,
  )
  return client
}

describe('VisibilityToggleButton', () => {
  beforeEach(() => {
    setSkillVisibility.mockReset().mockResolvedValue({
      skillId: 's1',
      visibility: 'PRIVATE',
      updatedAt: '2026-05-16T00:00:00Z',
    })
  })

  it('S184 AC-7: visibility=PUBLIC → 顯「轉為私人」', () => {
    renderToggle('s1', 'PUBLIC')
    expect(screen.getByRole('button', { name: '轉為私人' })).toBeInTheDocument()
  })

  it('S184 AC-7: visibility=PRIVATE → 顯「公開分享」', () => {
    renderToggle('s1', 'PRIVATE')
    expect(screen.getByRole('button', { name: '公開分享' })).toBeInTheDocument()
  })

  it('S184 AC-7: 點「轉為私人」→ PUT visibility PRIVATE，不查 grants', async () => {
    renderToggle('s1', 'PUBLIC')
    fireEvent.click(screen.getByRole('button', { name: '轉為私人' }))
    await waitFor(() => expect(setSkillVisibility).toHaveBeenCalledTimes(1))
    expect(setSkillVisibility).toHaveBeenCalledWith('s1', 'PRIVATE')
  })

  it('S184 AC-8: mutation success updates detail cache visibility', async () => {
    const client = renderToggle('s1', 'PUBLIC')
    fireEvent.click(screen.getByRole('button', { name: '轉為私人' }))
    await waitFor(() => {
      const cached = client.getQueryData<Skill>(skillKeys.detail('s1'))
      expect(cached?.visibility).toBe('PRIVATE')
      expect(cached?.updatedAt).toBe('2026-05-16T00:00:00Z')
    })
  })

  it('pending 期間 → 顯 disabled 處理中', async () => {
    setSkillVisibility.mockReturnValue(new Promise(() => {}))
    renderToggle('s1', 'PUBLIC')
    fireEvent.click(screen.getByRole('button', { name: '轉為私人' }))
    const btn = await screen.findByRole('button', { name: '轉為私人' })
    expect(btn).toBeDisabled()
    expect(btn.textContent).toBe('處理中...')
  })
})
