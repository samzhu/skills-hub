import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { EditSkillModal } from './EditSkillModal'
import * as skillsApi from '@/api/skills'
import type { Skill } from '@/types/skill'

vi.mock('@/api/skills', async () => {
  const actual = await vi.importActual<typeof import('@/api/skills')>('@/api/skills')
  return { ...actual, updateSkill: vi.fn() }
})

const updateSkillMock = vi.mocked(skillsApi.updateSkill)

const sampleSkill: Skill = {
  id: 's1',
  name: 'My Skill',
  description: 'original desc',
  author: 'alice',
  category: 'AI',
  latestVersion: '1.0.0',
  riskLevel: 'LOW',
  status: 'PUBLISHED',
  downloadCount: 0,
  averageRating: 0,
  reviewCount: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-05-01T00:00:00Z',
  verified: false,
  latestVersionPublishedAt: '2026-05-01T00:00:00Z',
  license: 'MIT',
  compatibility: [],
  versionCount: 1,
  openFlagCount: 0,
}

function renderModal(onClose = vi.fn(), skill = sampleSkill) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <EditSkillModal skill={skill} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { onClose }
}

describe('EditSkillModal', () => {
  beforeEach(() => {
    updateSkillMock.mockReset()
    updateSkillMock.mockResolvedValue(undefined)
  })

  it('S163 AC-7: prefill 當前 description / category', () => {
    renderModal()
    const desc = screen.getByLabelText(/描述/) as HTMLTextAreaElement
    const cat = screen.getByLabelText(/分類/) as HTMLInputElement
    expect(desc.value).toBe('original desc')
    expect(cat.value).toBe('AI')
  })

  it('儲存 button 預設 disabled（unchanged）', () => {
    renderModal()
    const btn = screen.getByRole('button', { name: /儲存/ })
    expect((btn as HTMLButtonElement).disabled).toBe(true)
  })

  it('S163 AC-1: 改 description + 點儲存 → updateSkill 收到新值 + onClose 觸發', async () => {
    const { onClose } = renderModal()
    const desc = screen.getByLabelText(/描述/) as HTMLTextAreaElement
    fireEvent.change(desc, { target: { value: 'new desc' } })

    const btn = screen.getByRole('button', { name: /儲存/ })
    fireEvent.click(btn)

    await waitFor(() => expect(updateSkillMock).toHaveBeenCalledTimes(1))
    expect(updateSkillMock).toHaveBeenCalledWith('s1', {
      description: 'new desc',
      category: 'AI',
    })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('description 空字串 → 儲存 disabled（不送空值）', () => {
    renderModal()
    const desc = screen.getByLabelText(/描述/) as HTMLTextAreaElement
    fireEvent.change(desc, { target: { value: '   ' } })
    const btn = screen.getByRole('button', { name: /儲存/ })
    expect((btn as HTMLButtonElement).disabled).toBe(true)
  })

  it('取消 → updateSkill 未呼叫 + onClose 觸發', () => {
    const { onClose } = renderModal()
    const desc = screen.getByLabelText(/描述/) as HTMLTextAreaElement
    fireEvent.change(desc, { target: { value: 'changed but cancelled' } })
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(updateSkillMock).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('UI 提示 name + version 不可在此編輯（避免 user 誤以為要送上來）', () => {
    renderModal()
    expect(screen.getByText(/技能名稱與版本號不可在此編輯/)).toBeTruthy()
  })
})
