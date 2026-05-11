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
  // S154b priority chain：name → email → handle → userId（**永遠不顯 raw sub**；handle/userId
  // 為 S154 backend 擴的 platform identity，displayLabel 與 fallbackChar 都對齊同 priority）。
  // 末層 `?? ''` 防全 priority chain 都 falsy 時 charAt(0) NPE（理論上不會發生 — backend
  // 強制 handle/userId NOT NULL — 但加 defensive 保 unit test mock 不完整時不炸）。
  const displayLabel = user.name ?? user.email ?? user.handle ?? user.userId ?? ''
  const fallbackChar = (displayLabel || '?').charAt(0).toUpperCase()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label="開啟使用者選單"
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
