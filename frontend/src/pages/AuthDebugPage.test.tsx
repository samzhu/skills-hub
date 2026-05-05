import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthDebugPage } from './AuthDebugPage'

/**
 * S134 AC-7 — `/auth-debug` 頁「我的認證」行為驗證。
 *
 * - 200 path：fetch /api/v1/dev/auth-debug 回 JSON → 頁面顯示 pretty-printed JSON
 * - 404 path：fetch 收 404（real-oauth profile 未啟用）→ 頁面顯示提示文字
 *
 * 對齊既有 page test pattern（NotificationsPage.test.tsx）：vi.fn() 全域 fetch mock + QueryClient
 * provider；不依賴 MSW（既有專案無 MSW infra）。
 */

const sampleClaimDump = {
  principal_name: 'test-sub-001',
  authorities: ['SCOPE_openid'],
  oidc_user_attributes: { sub: 'test-sub-001', name: 'Test User' },
  id_token_claims: {
    sub: 'test-sub-001',
    iss: 'https://auth-dev.omnihubs.cloud',
    aud: ['596527ca-...'],
  },
}

function renderWithProviders(ui: React.ReactNode) {
  // 每個 test 獨立 QueryClient 避免 staleTime cache 跨 test 污染
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>)
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AuthDebugPage', () => {
  it('AC-7: 200 — 顯示 pretty-printed JSON dump 含 principal / id_token_claims', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(sampleClaimDump), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    renderWithProviders(<AuthDebugPage />)

    // 標題立即可見（render-time）
    expect(await screen.findByText('我的認證')).toBeInTheDocument()
    // JSON 內容 stringify 後 <pre> 顯示；waitFor 等 fetch 完成
    await waitFor(() => {
      const pre = screen.getByTestId('auth-debug-json')
      expect(pre.textContent).toContain('test-sub-001')
      expect(pre.textContent).toContain('https://auth-dev.omnihubs.cloud')
      expect(pre.textContent).toContain('id_token_claims')
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/dev/auth-debug',
      expect.anything(),
    )
  })

  it('AC-7-fallback: 404 — 顯示「未啟用真實 OAuth profile」提示，不渲染 JSON pre', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(null, { status: 404 }),
    )

    renderWithProviders(<AuthDebugPage />)

    expect(await screen.findByText(/未啟用真實 OAuth profile/)).toBeInTheDocument()
    // pre 區塊應不渲染
    expect(screen.queryByTestId('auth-debug-json')).toBeNull()
  })
})
