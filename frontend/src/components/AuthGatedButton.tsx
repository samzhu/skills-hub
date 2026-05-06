import type { ButtonHTMLAttributes } from 'react'
import { useAuth } from '../hooks/useAuth'

/**
 * S139 — Lazy auth gate button (per spec §4.3).
 *
 * <p>包裝原生 `<button>`：點擊時 useAuth.status 分支：
 * <ul>
 *   <li>{@code authenticated} → 呼叫傳入的 {@code onClick}</li>
 *   <li>{@code anonymous} / {@code loading} → 呼叫 {@code useAuth.login()}
 *       跳 OAuth flow，returnTo 預設為當前 pathname + search（在 useAuth 內處理）</li>
 * </ul>
 *
 * <p>Loading 狀態 click 視同 anonymous redirect login — 用戶 race click 時
 * 直接走登入 flow；若實際已登入，OAuth provider 認 session 短路回來，slight
 * friction 但不破壞 lazy gate 語義。Spec §4.3 「loading 狀態維持 enabled」是視覺
 * 約束（不要閃爍 disabled），點擊行為不在約束內。
 *
 * <p>透傳所有 standard {@code <button>} HTML props（className / disabled / type
 * 等），由 caller 控制 styling。caller 給的 {@code onClick} 是 () => void
 * 純動作（不接 MouseEvent），對應「執行該動作」的 intent；event handler 包裝在
 * 內部處理 lazy gate 後再呼叫。
 */
type AuthGatedButtonProps = Omit<
  ButtonHTMLAttributes<HTMLButtonElement>,
  'onClick'
> & {
  onClick: () => void
}

export function AuthGatedButton({ onClick, children, ...rest }: AuthGatedButtonProps) {
  const auth = useAuth()
  const handleClick = () => {
    if (auth.status === 'authenticated') {
      onClick()
    } else {
      // anonymous OR loading — 走 lazy gate redirect login（returnTo 用當前頁面）
      auth.login()
    }
  }
  return (
    <button {...rest} onClick={handleClick}>
      {children}
    </button>
  )
}
