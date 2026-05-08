import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthDebugPage } from './AuthDebugPage'

/**
 * S134 AC-7 — `/auth-debug` 頁「我的認證」行為驗證。
 *
 * - 200 path：fetch /api/v1/dev/auth-debug 回 JSON → 頁面顯示 pretty-printed JSON
 * - 404 path：fetch 收 404（real-oauth profile 未啟用）→ S155 #2 EmptyState 提示
 *
 * S155 #2: 頁面加 AppShell wrapper；no-oauth fallback 改 EmptyState 友善文案。
 * Test 對應補 MemoryRouter wrap（AppShell 內 react-router Link 需 router context）；
 * /api/v1/me 預設 mock 401 讓 useAuth 走 anonymous 分支（AppShell 不啟 bell polling）。
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
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/auth-debug']}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

const fetchMock = vi.fn()

/** Route-aware mock：auth-debug 走 testcase 設定；/me 走 anonymous；其餘 default 404。 */
function setupAuthDebugMock(authDebugResponse: Response) {
  fetchMock.mockImplementation((url: string) => {
    const u = String(url)
    if (u.includes('/api/v1/dev/auth-debug')) {
      return Promise.resolve(authDebugResponse)
    }
    if (u.includes('/api/v1/me')) {
      return Promise.resolve(new Response(null, { status: 401 }))
    }
    return Promise.resolve(new Response(null, { status: 404 }))
  })
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AuthDebugPage', () => {
  it('AC-7: 200 — 顯示 pretty-printed JSON dump 含 principal / id_token_claims', async () => {
    setupAuthDebugMock(
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

  it('AC-7-fallback / S155 #2: 404 → EmptyState 友善文案 + 「返回首頁」CTA', async () => {
    setupAuthDebugMock(new Response(null, { status: 404 }))

    renderWithProviders(<AuthDebugPage />)

    // 友善 headline 取代「SPRING_PROFILES_ACTIVE」dev jargon
    expect(await screen.findByText('此功能僅在開發環境啟用')).toBeInTheDocument()
    // 一般 user 退路 — 「返回首頁」連到 /browse
    const cta = screen.getByText('返回首頁').closest('a')
    expect(cta).toHaveAttribute('href', '/browse')
    // pre 區塊應不渲染
    expect(screen.queryByTestId('auth-debug-json')).toBeNull()
    // 不再洩漏 dev jargon 給普通 user
    expect(screen.queryByText(/SPRING_PROFILES_ACTIVE/)).toBeNull()
  })
})
