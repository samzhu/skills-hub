/**
 * S139 — AuthArea component tests.
 *
 * 覆蓋 3 種 status 渲染：loading（skeleton）/ anonymous（登入按鈕）/ authenticated
 * （avatar trigger，可開 dropdown 看 email + 登出）。
 *
 * 透過 vi.mock 替換 useAuth hook 三段狀態回傳值，不打真實 /api/v1/me。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AuthArea } from './AuthArea'
import * as useAuthModule from '../hooks/useAuth'

vi.mock('../hooks/useAuth')

const mockUseAuth = vi.mocked(useAuthModule.useAuth)

const renderAuthArea = () =>
  render(
    <MemoryRouter>
      <AuthArea />
    </MemoryRouter>,
  )

describe('AuthArea — S139 header right slot', () => {
  it('AC-1: status=anonymous → 登入 button 渲染', () => {
    const loginSpy = vi.fn()
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login: loginSpy,
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    renderAuthArea()
    const btn = screen.getByRole('button', { name: '登入' })
    expect(btn).toBeInTheDocument()
    fireEvent.click(btn)
    expect(loginSpy).toHaveBeenCalled()
  })

  it('AC-1 (loading): status=loading → 不顯示登入按鈕也不顯示 avatar（skeleton placeholder）', () => {
    mockUseAuth.mockReturnValue({
      status: 'loading',
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    renderAuthArea()
    expect(screen.queryByRole('button', { name: '登入' })).toBeNull()
    expect(screen.queryByRole('button', { name: /open user menu/i })).toBeNull()
  })

  it('AC-4: status=authenticated → avatar trigger 渲染（含 picture + aria-haspopup）', () => {
    mockUseAuth.mockReturnValue({
      status: 'authenticated',
      user: {
        sub: 'alice',
        email: 'alice@example.com',
        name: 'Alice',
        picture: 'https://example.com/alice.png',
      },
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    renderAuthArea()
    const trigger = screen.getByRole('button', { name: /open user menu/i })
    expect(trigger).toBeInTheDocument()
    // Radix DropdownMenuTrigger 自動加 aria-haspopup="menu"
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu')
    // avatar img 帶 user.picture URL
    const img = trigger.querySelector('img')
    expect(img?.getAttribute('src')).toBe('https://example.com/alice.png')

    // dropdown 開啟後 menu item 互動屬 Radix Portal jsdom 不友善，
    // 留 E2E（T04 LAB smoke）覆蓋；本 unit 驗 trigger structure 即足
  })

  it('AC-4: 登入用戶無 picture 時 avatar fallback 顯示首字母', () => {
    mockUseAuth.mockReturnValue({
      status: 'authenticated',
      user: {
        sub: 'bob',
        email: 'bob@example.com',
        name: 'Bob',
      },
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    renderAuthArea()
    // 沒 picture 時 avatar 顯示 'B'（email 首字母大寫）或 'b'（fallback）
    const trigger = screen.getByRole('button', { name: /open user menu/i })
    expect(trigger.textContent?.toUpperCase()).toContain('B')
  })
})
