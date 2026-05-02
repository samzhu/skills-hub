import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'

/**
 * 導覽連結定義。
 * `as const` 讓 TypeScript 推斷 path 的字面量型別（而非 string），
 * 確保型別安全並避免誤填無效路徑。
 */
const navLinks = [
  // S096e1: Browse 改 /browse；/ 改為 public Landing page
  { path: '/browse', label: '瀏覽' },
  { path: '/requests', label: '需求' }, // S096g1: Request Board stub
  { path: '/my-skills', label: '我的技能' }, // S094a: author dashboard
  { path: '/publish', label: '發佈' },
  { path: '/analytics', label: '數據' },
  // S094d: docs entry point；指向第一篇 walkthrough，未來 /docs 可改 index 頁
  { path: '/docs/your-first-skill', label: '文件' },
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

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-50 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-6 px-6">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <span className="text-lg">Skills Hub</span>
          </Link>
          <nav className="flex items-center gap-4">
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
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
    </div>
  )
}
