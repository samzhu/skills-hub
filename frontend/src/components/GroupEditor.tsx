import type { GroupSearchResult, GroupTreeNode } from '@/api/groups'

interface GroupEditorProps {
  group: GroupTreeNode
  path: string[]
  memberCount: number
}

const kindLabels: Record<GroupSearchResult['kind'], string> = {
  COMPANY: '公司',
  DEPARTMENT: '部門',
  TEAM: '團隊',
  OTHER: '其他',
}

/**
 * S170 — read-only management panel shell for selected group details.
 */
export function GroupEditor({ group, path, memberCount }: GroupEditorProps) {
  return (
    <section className="rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-[12px] font-medium text-muted-foreground">{path.join(' / ')}</p>
          <h2 className="mt-1 text-[22px] font-semibold tracking-tight">{group.displayName}</h2>
          <p className="mt-1 text-[12px] text-muted-foreground">Principal：{group.principalKey}</p>
        </div>
        <span className="rounded-md border border-border px-2.5 py-1 text-[12px] text-muted-foreground">
          {kindLabels[group.kind]}
        </span>
      </div>

      <div className="mt-5 flex flex-wrap gap-2">
        <button type="button" className="rounded-md bg-primary px-3 py-2 text-[13px] font-medium text-primary-foreground">
          新增子群組
        </button>
        <button type="button" className="rounded-md border border-border px-3 py-2 text-[13px] font-medium hover:bg-muted">
          新增成員
        </button>
        <button type="button" className="rounded-md border border-border px-3 py-2 text-[13px] font-medium text-muted-foreground hover:bg-muted">
          封存群組
        </button>
      </div>

      <div className="mt-6 grid gap-4 lg:grid-cols-2">
        <section className="rounded-lg border border-border p-4">
          <h3 className="text-[14px] font-semibold">子群組</h3>
          {group.children.length === 0 ? (
            <p className="mt-3 text-[13px] text-muted-foreground">尚未建立子群組。</p>
          ) : (
            <ul className="mt-3 space-y-2">
              {group.children.map((child) => (
                <li key={child.id} className="rounded-md bg-muted px-3 py-2 text-[13px]">
                  {child.displayName}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="rounded-lg border border-border p-4">
          <h3 className="text-[14px] font-semibold">成員</h3>
          <p className="mt-3 text-[13px] text-muted-foreground">目前有 {memberCount} 位直接成員</p>
          <div className="mt-3 rounded-md bg-muted px-3 py-2 text-[13px] text-muted-foreground">
            成員明細會在後續權限管理任務接上。
          </div>
        </section>
      </div>
    </section>
  )
}
