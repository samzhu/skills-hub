import type { ReactNode } from 'react'
import { AppShell } from './AppShell'
import { DocsSidebar } from './DocsSidebar'

/**
 * S094d — Docs page layout：AppShell chrome + sidebar + main column。
 *
 * Main column 限寬 680px（per prototype `.dc-main`）+ 左側 224px sidebar，
 * sub-680px 視窗 sidebar 自動消失（mobile reading）。
 */
export function DocsLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell>
      <div className="flex gap-8">
        <div className="hidden md:block">
          <DocsSidebar />
        </div>
        <main className="min-w-0 max-w-[680px] flex-1">{children}</main>
      </div>
    </AppShell>
  )
}
