import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthErrorPage } from './AuthErrorPage'

const renderAuthError = (entry: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/auth/error" element={<AuthErrorPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AuthErrorPage — S204 OAuth failure recovery', () => {
  it('AC-S204-2: /auth/error without reason renders generic recovery page', () => {
    renderAuthError('/auth/error')

    expect(screen.getByRole('heading', { level: 2, name: '登入沒有完成' })).toBeInTheDocument()
    expect(screen.getByText(/請回到瀏覽頁面後再重新開始/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /返回瀏覽/ })).toHaveAttribute('href', '/browse')
    expect(screen.getByText('錯誤代碼：oauth_failed')).toBeInTheDocument()
    expect(screen.queryByText(/Invalid credentials/i)).not.toBeInTheDocument()
  })

  it('AC-S204-3: token_exchange_failed shows backend authentication copy and Cloud Run hint', () => {
    renderAuthError('/auth/error?reason=token_exchange_failed')

    expect(screen.getByRole('heading', { level: 2, name: '登入沒有完成' })).toBeInTheDocument()
    expect(screen.getByText(/Google 已回到 Skills Hub，但後端沒有完成認證/)).toBeInTheDocument()
    expect(screen.getByText(/Cloud Run 已開新 revision/)).toBeInTheDocument()
    expect(screen.getByText('錯誤代碼：token_exchange_failed')).toBeInTheDocument()
  })

  it('AC-S204-4: access_denied shows consent copy without Cloud Run hint', () => {
    renderAuthError('/auth/error?reason=access_denied')

    expect(screen.getByText(/Google 沒有授權這次登入/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /返回瀏覽/ })).toHaveAttribute('href', '/browse')
    expect(screen.queryByText(/Cloud Run 已開新 revision/)).not.toBeInTheDocument()
  })

  it('AC-S204-5: session_expired primary action links to /browse', () => {
    renderAuthError('/auth/error?reason=session_expired')

    expect(screen.getByText(/這次登入流程已失效，請回到瀏覽頁面後再重新開始/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /返回瀏覽/ })).toHaveAttribute('href', '/browse')
  })

  it('AC-S204-2: unknown reason is normalized and raw query is not rendered', () => {
    renderAuthError('/auth/error?reason=raw-token-secret&code=secret-code&error=Invalid%20credentials')

    expect(screen.getByText('錯誤代碼：oauth_failed')).toBeInTheDocument()
    const pageText = document.body.textContent ?? ''
    expect(pageText).not.toContain('raw-token-secret')
    expect(pageText).not.toContain('secret-code')
    expect(pageText).not.toContain('Invalid credentials')
  })
})
