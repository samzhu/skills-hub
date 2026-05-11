import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import {
  useIsSubscribed,
  useMySubscriptionDetails,
  useSubscribeSkill,
  useUnsubscribeSkill,
} from './useSubscription'

function createWrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('useSubscription — S145 details hook', () => {
  it('AC-S145-5: useIsSubscribed still derives from GET /me/subscriptions string[]', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(['skill-1']),
    } as Response)

    const { result } = renderHook(() => useIsSubscribed('skill-1'), {
      wrapper: createWrapper(client),
    })

    await waitFor(() => expect(result.current).toBe(true))
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/v1/me/subscriptions', undefined)
  })

  it('AC-S145-2: useMySubscriptionDetails fetches /me/subscriptions/details summary rows', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([
        {
          skillId: 'skill-1',
          skillName: 'deep-research',
          author: 'u_author1',
          authorDisplayName: 'Sam Zhu',
          latestVersion: '1.2.0',
          riskLevel: 'LOW',
          status: 'PUBLISHED',
          subscribedAt: '2026-05-08T10:15:30Z',
        },
      ]),
    } as Response)

    const { result } = renderHook(() => useMySubscriptionDetails(), {
      wrapper: createWrapper(client),
    })

    await waitFor(() => expect(result.current.data?.[0].skillName).toBe('deep-research'))
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/v1/me/subscriptions/details', undefined)
  })

  it('AC-S145-3: subscribe/unsubscribe mutations invalidate id-list and details caches', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true } as Response)

    const { result: subscribe } = renderHook(() => useSubscribeSkill(), {
      wrapper: createWrapper(client),
    })
    const { result: unsubscribe } = renderHook(() => useUnsubscribeSkill(), {
      wrapper: createWrapper(client),
    })

    await act(async () => {
      await subscribe.current.mutateAsync('skill-1')
      await unsubscribe.current.mutateAsync('skill-1')
    })

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['my-subscriptions'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['my-subscriptions', 'details'] })
  })
})
