import { Link } from 'react-router'
import { useAuth } from '../hooks/useAuth'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'

/**
 * S139 — AppShell header right slot：根據 useAuth status 切換 3 種 UI。
 *
 * <p>未登入：「登入」 button → 點擊跳 OAuth flow（useAuth.login 已處理 returnTo）。
 * 登入中：skeleton placeholder 避免閃爍（短暫 200ms ~ 1s）。
 * 已登入：圓形 avatar trigger（user.picture URL 或 fallback 首字母）；
 *         dropdown 含 email / 我的技能 / 登出。
 *
 * <p>不依賴 shadcn Button 元件（專案目前無 button.tsx 元件）；用 Tailwind primitive。
 */
export function AuthArea() {
  const auth = useAuth()

  if (auth.status === 'loading') {
    // skeleton — 寬高對齊登入按鈕避免 layout shift
    return <div className="h-8 w-20 animate-pulse rounded-md bg-muted" aria-hidden />
  }

  if (auth.status === 'anonymous') {
    return (
      <button
        type="button"
        onClick={() => auth.login()}
        className="inline-flex h-8 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium hover:bg-accent hover:text-accent-foreground"
      >
        登入
      </button>
    )
  }

  // authenticated
  const { user } = auth
  // avatar fallback 首字母：name → email → sub 順序取首字
  const fallbackChar = (user.name ?? user.email ?? user.sub).charAt(0).toUpperCase()
  const displayLabel = user.email ?? user.name ?? user.sub

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label="Open user menu"
          className="flex h-8 w-8 items-center justify-center overflow-hidden rounded-full border bg-muted text-sm font-medium hover:bg-accent"
        >
          {user.picture ? (
            <img src={user.picture} alt="" className="h-full w-full object-cover" />
          ) : (
            <span>{fallbackChar}</span>
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="truncate">{displayLabel}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link to="/my-skills">我的技能</Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => auth.logout()}>登出</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
