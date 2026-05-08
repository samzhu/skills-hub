import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { Bell } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { Toaster } from 'sonner'
import { fetchUnreadCount } from '@/api/notifications'
import { useAuth } from '@/hooks/useAuth'
import { AuthArea } from './AuthArea'

/**
 * 導覽連結定義。
 * `as const` 讓 TypeScript 推斷 path 的字面量型別（而非 string），
 * 確保型別安全並避免誤填無效路徑。
 */
const navLinks = [
  // S096e1: Browse 改 /browse；/ 改為 public Landing page
  { path: '/browse', label: '瀏覽' },
  { path: '/collections', label: '集合' }, // S096f1: Collections stub
  { path: '/requests', label: '需求' }, // S096g1: Request Board stub
  { path: '/my-skills', label: '我的技能' }, // S094a: author dashboard
  { path: '/publish', label: '發佈' },
  { path: '/analytics', label: '數據' },
  { path: '/flags', label: '待審回報' }, // S098e3-T04: reviewer queue
  // S143: /docs canonical entry — 由 App.tsx redirect 接走至 /docs/overview
  { path: '/docs', label: '文件' },
] as const

/**
 * 應用程式外殼（Layout）元件：提供黏著式頂部導覽列及置中的 `<main>` 內容區域。
 *
 * 導覽列使用精確路徑比對（`pathname === path`）高亮當前頁籤；
 * 子路由（如 `/skills/123`）不會觸發 `/` 的高亮，屬預期行為。
 *
 * @param children 頁面內容
 */
export function AppShell({ children }: { children: ReactNode }) {
  const location = useLocation()
  // S139: bell + unread poll 改 conditional on authenticated — anonymous user 不
  // 該 hit /notifications/unread-count（會吃 401 噪訊）；登入後才啟用 polling。
  const auth = useAuth()
  const isAuthenticated = auth.status === 'authenticated'
  // S096h1: bell badge polls unread count every 30s (per Engineering Handoff §2.17)
  const { data: unread } = useQuery({
    queryKey: ['notifications-unread'],
    queryFn: fetchUnreadCount,
    refetchInterval: 30 * 1000,
    staleTime: 25 * 1000,
    enabled: isAuthenticated,
  })
  const unreadCount = unread?.count ?? 0

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-50 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-6">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <span className="text-lg">Skills Hub</span>
          </Link>
          <nav className="flex flex-1 items-center gap-4">
            {navLinks.map(({ path, label }) => (
              <Link
                key={path}
                to={path}
                className={`text-sm ${location.pathname === path ? 'text-foreground font-medium' : 'text-muted-foreground hover:text-foreground'}`}
              >
                {label}
              </Link>
            ))}
          </nav>
          {/* S096h1 + S139: bell 只在登入後渲染 — anonymous 看不到 unread badge / 鈴鐺 */}
          {isAuthenticated && (
            <Link
              to="/notifications"
              aria-label="通知"
              className="relative flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <Bell className="h-4 w-4" />
              {unreadCount > 0 && (
                <span
                  className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full px-1 font-mono text-[10px] font-medium text-white"
                  style={{ backgroundColor: '#E24B4A' }}
                >
                  {unreadCount > 99 ? '99+' : unreadCount}
                </span>
              )}
            </Link>
          )}
          {/* S139: AuthArea — 未登入：登入按鈕；登入：avatar dropdown */}
          <AuthArea />
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
      <Toaster />
    </div>
  )
}
