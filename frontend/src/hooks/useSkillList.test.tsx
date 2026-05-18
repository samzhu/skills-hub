import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSkillList } from './useSkillList'
import * as skillsApi from '../api/skills'

vi.mock('../api/skills')

const mockFetchSkills = vi.mocked(skillsApi.fetchSkills)

const createWrapper = () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('useSkillList', () => {
  beforeEach(() => {
    mockFetchSkills.mockReset()
    mockFetchSkills.mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 },
    })
  })

  it('AC-S189-2: enabled false stops catalog list fetch', () => {
    renderHook(() => useSkillList({ page: 0, size: 20 }, { enabled: false }), {
      wrapper: createWrapper(),
    })
    expect(mockFetchSkills).not.toHaveBeenCalled()
  })

  it('AC-S189-1: existing callers without options still fetch catalog list', async () => {
    const { result } = renderHook(() => useSkillList({ page: 0, size: 20 }), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetchSkills).toHaveBeenCalledWith({ page: 0, size: 20 })
  })
})
