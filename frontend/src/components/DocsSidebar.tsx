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
    label: 'Getting started',
    items: [
      { label: 'Overview' }, // future S094d-extension
      { label: 'Your first skill', path: '/docs/your-first-skill' },
    ],
  },
  {
    label: 'Reference',
    items: [
      { label: 'SKILL.md spec' },
      { label: 'Frontmatter fields' },
      { label: 'Bundle structure' },
      { label: 'Risk tiers' },
    ],
  },
  {
    label: 'Publishing',
    items: [
      { label: 'Upload & validation' },
      { label: 'Versioning' },
      { label: 'Semantic search' },
    ],
  },
  {
    label: 'API & webhooks',
    items: [
      { label: 'REST reference' },
      { label: 'Event payloads' },
    ],
  },
]

export function DocsSidebar() {
  const location = useLocation()
  return (
    <aside className="w-56 shrink-0 border-r border-[#E6E1D9] pr-6">
      <nav className="flex flex-col gap-5">
        {GROUPS.map((g) => (
          <div key={g.label} className="flex flex-col gap-1.5">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-[#A09B96]">
              {g.label}
            </p>
            {g.items.map((it) => {
              const isActive = it.path === location.pathname
              if (!it.path) {
                return (
                  <span
                    key={it.label}
                    className="cursor-not-allowed text-[13px] text-[#C5C0BC]"
                    title="Coming soon"
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
                      ? 'rounded-md bg-[#EEEDFE] px-2 py-1 text-[13px] font-medium text-[#3C3489]'
                      : 'px-2 py-1 text-[13px] text-[#5C5751] hover:text-[#181818]'
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
