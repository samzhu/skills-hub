/**
 * S094d — Docs left sidebar with 4 IA groups（per prototype `dc-side-group`）。
 *
 * 目前只「Your first skill」是 active link；其他 item 為 placeholder（dimmed +
 * 不可點），等後續 docs spec 補。Sidebar group 結構保留是為了讓使用者一眼看到
 * docs 體系完整 IA — 即使內容未填，預期管理（discovery）已建立。
 */
import { Link, useLocation } from 'react-router'

interface SidebarItem {
  label: string
  path?: string // 有 path 為 active link，無則 placeholder
}

interface SidebarGroup {
  label: string
  items: SidebarItem[]
}

const GROUPS: SidebarGroup[] = [
  {
    label: '入門',
    items: [
      { label: '概覽', path: '/docs/overview' },
      { label: '撰寫第一個技能', path: '/docs/your-first-skill' },
    ],
  },
  {
    label: '參考',
    items: [
      { label: 'SKILL.md 規範', path: '/docs/skill-md-spec' },
      { label: 'Frontmatter 欄位', path: '/docs/frontmatter' },
      { label: 'Bundle 結構', path: '/docs/bundle' },
      { label: '風險層級', path: '/docs/risk-tiers' },
    ],
  },
  {
    label: '發佈',
    items: [
      { label: '上傳與驗證' },
      { label: '版本管理' },
      { label: '語意搜尋' },
    ],
  },
  {
    label: 'API 與 Webhook',
    items: [
      { label: 'REST 參考' },
      { label: 'Event payload' },
    ],
  },
]

export function DocsSidebar() {
  const location = useLocation()
  return (
    <aside className="w-56 shrink-0 border-r border-[rgba(255,255,255,0.06)] pr-6">
      <nav className="flex flex-col gap-5">
        {GROUPS.map((g) => (
          <div key={g.label} className="flex flex-col gap-1.5">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-[#A8A49C]">
              {g.label}
            </p>
            {g.items.map((it) => {
              const isActive = it.path === location.pathname
              if (!it.path) {
                return (
                  <span
                    key={it.label}
                    className="cursor-not-allowed text-[13px] text-[#5E5B55]"
                    title="即將推出"
                  >
                    {it.label}
                  </span>
                )
              }
              return (
                <Link
                  key={it.label}
                  to={it.path}
                  className={
                    isActive
                      ? 'rounded-md bg-[rgba(127,119,221,0.10)] px-2 py-1 text-[13px] font-medium text-[#C9C5F2]'
                      : 'px-2 py-1 text-[13px] text-[#A8A49C] hover:text-[#EEECEA]'
                  }
                >
                  {it.label}
                </Link>
              )
            })}
          </div>
        ))}
      </nav>
    </aside>
  )
}
