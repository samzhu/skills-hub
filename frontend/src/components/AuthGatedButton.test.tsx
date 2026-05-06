/**
 * S139 AC-3 — AuthGatedButton tests.
 *
 * Lazy gate pattern：button click 時：
 *   - authenticated → 呼叫傳入的 onClick
 *   - anonymous / loading → 呼叫 useAuth().login()（跳 OAuth）
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AuthGatedButton } from './AuthGatedButton'
import * as useAuthModule from '../hooks/useAuth'

vi.mock('../hooks/useAuth')

const mockUseAuth = vi.mocked(useAuthModule.useAuth)

describe('AuthGatedButton — S139 AC-3 lazy gate', () => {
  it('AC-3: anonymous → click 觸發 useAuth.login()，不呼叫 onClick', () => {
    const onClickSpy = vi.fn()
    const loginSpy = vi.fn()
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login: loginSpy,
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    render(<AuthGatedButton onClick={onClickSpy}>Submit</AuthGatedButton>)
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(loginSpy).toHaveBeenCalledTimes(1)
    expect(onClickSpy).not.toHaveBeenCalled()
  })

  it('authenticated → click 呼叫 onClick，不呼叫 login', () => {
    const onClickSpy = vi.fn()
    const loginSpy = vi.fn()
    mockUseAuth.mockReturnValue({
      status: 'authenticated',
      user: { sub: 'alice', email: 'alice@example.com', name: 'Alice' },
      login: loginSpy,
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    render(<AuthGatedButton onClick={onClickSpy}>Upload</AuthGatedButton>)
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(onClickSpy).toHaveBeenCalledTimes(1)
    expect(loginSpy).not.toHaveBeenCalled()
  })

  it('loading → click 視同 anonymous（redirect login）', () => {
    const onClickSpy = vi.fn()
    const loginSpy = vi.fn()
    mockUseAuth.mockReturnValue({
      status: 'loading',
      login: loginSpy,
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    render(<AuthGatedButton onClick={onClickSpy}>Submit</AuthGatedButton>)
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(loginSpy).toHaveBeenCalledTimes(1)
    expect(onClickSpy).not.toHaveBeenCalled()
  })

  it('支援 className / disabled / type 等 standard button props 透傳', () => {
    mockUseAuth.mockReturnValue({
      status: 'anonymous',
      login: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuthModule.useAuth>)

    render(
      <AuthGatedButton
        onClick={() => {}}
        className="custom-class"
        disabled
        type="submit"
      >
        Click
      </AuthGatedButton>,
    )
    const btn = screen.getByRole('button', { name: 'Click' }) as HTMLButtonElement
    expect(btn.className).toContain('custom-class')
    expect(btn.disabled).toBe(true)
    expect(btn.type).toBe('submit')
  })
})
