/**
 * S139 — `useAuth` hook tests.
 *
 * 覆蓋 AC-1（fetchMe 200 → authenticated）/ AC-1 + AC-7（fetchMe 401 → anonymous）/
 * login(returnTo) 行為（拼 URL 跳 OAuth）/ logout()（POST /logout 後 reload）。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useAuth } from './useAuth'

const wrapper = ({ children }: { children: ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

const originalLocation = window.location

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  // restore window.location after each test
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: originalLocation,
  })
})

describe('useAuth — S139 fetchMe + login/logout', () => {
  it('AC-1: fetchMe 200 → status="authenticated" + user payload', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          sub: 'alice',
          email: 'alice@example.com',
          name: 'Alice',
          picture: 'https://example.com/alice.png',
        }),
    } as Response)

    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('authenticated'))
    if (result.current.status === 'authenticated') {
      expect(result.current.user.sub).toBe('alice')
      expect(result.current.user.email).toBe('alice@example.com')
    }
  })

  it('AC-1 / AC-7: fetchMe 401 → status="anonymous"（不 throw）', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ error: 'unauthorized' }),
    } as Response)

    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('anonymous'))
  })

  it('AC-3: login(returnTo) 拼 /oauth2/authorization/skillshub?returnTo=<encoded>', () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({}),
    } as Response)

    // mock window.location with assignable href
    const hrefSpy = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        ...originalLocation,
        get href() {
          return ''
        },
        set href(value: string) {
          hrefSpy(value)
        },
        pathname: '/publish',
        search: '?draft=1',
      },
    })

    const { result } = renderHook(() => useAuth(), { wrapper })
    result.current.login('/publish?draft=1')

    expect(hrefSpy).toHaveBeenCalledWith(
      '/oauth2/authorization/skillshub?returnTo=%2Fpublish%3Fdraft%3D1',
    )
  })

  it('AC-3: login() 無參數時用 current pathname + search', () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({}),
    } as Response)

    const hrefSpy = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        ...originalLocation,
        get href() {
          return ''
        },
        set href(value: string) {
          hrefSpy(value)
        },
        pathname: '/collections',
        search: '',
      },
    })

    const { result } = renderHook(() => useAuth(), { wrapper })
    result.current.login()

    expect(hrefSpy).toHaveBeenCalledWith(
      '/oauth2/authorization/skillshub?returnTo=%2Fcollections',
    )
  })

  it('AC-5: logout() POST /logout 後 reload', async () => {
    let postLogoutCalled = false
    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/logout' && init?.method === 'POST') {
        postLogoutCalled = true
        return Promise.resolve({ ok: true, status: 200 } as Response)
      }
      return Promise.resolve({
        ok: false,
        status: 401,
        json: () => Promise.resolve({}),
      } as Response)
    })

    const reloadSpy = vi.fn()
    const hrefSpy = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        ...originalLocation,
        get href() {
          return ''
        },
        set href(value: string) {
          hrefSpy(value)
        },
        pathname: '/browse',
        search: '',
        reload: reloadSpy,
      },
    })

    const { result } = renderHook(() => useAuth(), { wrapper })
    await result.current.logout()

    expect(postLogoutCalled).toBe(true)
    // logout 後跳 / + 觸發 reload（任一 path 即可）
    expect(hrefSpy.mock.calls.some((c) => c[0] === '/') || reloadSpy.mock.calls.length > 0).toBe(true)
  })
})
