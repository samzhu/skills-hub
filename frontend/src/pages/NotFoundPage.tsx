import { Link } from 'react-router'
import { AppShell } from '@/components/AppShell'

/**
 * 處理 React Router unmatched URL — 沒這個 route 之前任何拼錯的網址（含 `/skills`
 * 列表頁等使用者直覺會試的 alias）會 render 空白 root，user 體驗為「網站壞掉」。
 */
export function NotFoundPage() {
  return (
    <AppShell>
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-4 px-6 py-24 text-center">
        <h1 className="text-4xl font-semibold">404</h1>
        <p className="text-muted-foreground">找不到此頁面。</p>
        <Link
          to="/"
          className="text-primary underline-offset-4 hover:underline"
        >
          回到首頁
        </Link>
      </div>
    </AppShell>
  )
}
