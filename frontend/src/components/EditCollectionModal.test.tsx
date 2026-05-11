import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { EditCollectionModal } from './EditCollectionModal'
import * as skillsApi from '@/api/skills'
import type { CollectionDetail } from '@/api/skills'

vi.mock('@/api/skills', async () => {
  const actual = await vi.importActual<typeof import('@/api/skills')>('@/api/skills')
  return { ...actual, updateCollection: vi.fn() }
})

const updateCollectionMock = vi.mocked(skillsApi.updateCollection)

const sampleCollection: CollectionDetail = {
  id: 'c1',
  name: 'Security Audit Pack',
  description: 'Audit tools for terraform and k8s',
  category: 'Security',
  ownerId: 'alice',
  installCount: 5,
  createdAt: '2026-05-01T00:00:00Z',
  skills: [
    { id: 'sk-1', name: 'tf', category: 'Security', riskLevel: 'HIGH', latestVersion: '1.0.0' },
    { id: 'sk-2', name: 'k8s', category: 'DevOps', riskLevel: 'MEDIUM', latestVersion: '2.1.0' },
  ],
}

function renderModal(onClose = vi.fn(), collection = sampleCollection) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <EditCollectionModal collection={collection} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { onClose }
}

describe('EditCollectionModal (S164b)', () => {
  beforeEach(() => {
    updateCollectionMock.mockReset()
    updateCollectionMock.mockResolvedValue(undefined)
  })

  it('S164 AC-1: prefill 當前 name / description / category / skillIds', () => {
    renderModal()
    expect((screen.getByLabelText(/名稱/) as HTMLInputElement).value).toBe('Security Audit Pack')
    expect((screen.getByLabelText(/說明/) as HTMLTextAreaElement).value).toBe('Audit tools for terraform and k8s')
    expect((screen.getByLabelText(/分類/) as HTMLInputElement).value).toBe('Security')
    expect((screen.getByLabelText(/技能 ID 清單/) as HTMLTextAreaElement).value).toBe('sk-1\nsk-2')
  })

  it('儲存 button 預設 disabled（unchanged）', () => {
    renderModal()
    expect((screen.getByRole('button', { name: /儲存/ }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('S164 AC-1: 改 name 後儲存 → updateCollection 收到新值 + onClose', async () => {
    const { onClose } = renderModal()
    fireEvent.change(screen.getByLabelText(/名稱/), { target: { value: 'Updated Name' } })
    fireEvent.click(screen.getByRole('button', { name: /儲存/ }))

    await waitFor(() => expect(updateCollectionMock).toHaveBeenCalledTimes(1))
    expect(updateCollectionMock).toHaveBeenCalledWith('c1', {
      name: 'Updated Name',
      description: 'Audit tools for terraform and k8s',
      category: 'Security',
      skillIds: ['sk-1', 'sk-2'],
    })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('S164 AC-6: 改 skillIds 整段覆蓋（不 append）', async () => {
    renderModal()
    fireEvent.change(screen.getByLabelText(/技能 ID 清單/), {
      target: { value: 'new-a\nnew-b\nnew-c' },
    })
    fireEvent.click(screen.getByRole('button', { name: /儲存/ }))
    await waitFor(() => expect(updateCollectionMock).toHaveBeenCalled())
    expect(updateCollectionMock.mock.calls[0]?.[1]?.skillIds).toEqual(['new-a', 'new-b', 'new-c'])
  })

  it('取消 → updateCollection 未呼叫 + onClose', () => {
    const { onClose } = renderModal()
    fireEvent.change(screen.getByLabelText(/名稱/), { target: { value: 'changed' } })
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(updateCollectionMock).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('name 空字串 → 儲存 disabled', () => {
    renderModal()
    fireEvent.change(screen.getByLabelText(/名稱/), { target: { value: '   ' } })
    expect((screen.getByRole('button', { name: /儲存/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})
