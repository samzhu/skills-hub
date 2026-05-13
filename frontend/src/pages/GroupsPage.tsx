import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { GroupTree } from '@/components/GroupTree'
import { GroupEditor } from '@/components/GroupEditor'
import { fetchGroupTree, searchGroups, type GroupTreeNode } from '@/api/groups'

/**
 * S170 — group management page for browsing the Group tree and selected Group details.
 */
export function GroupsPage() {
  const { data: groups, isLoading } = useQuery({
    queryKey: ['groups-tree'],
    queryFn: fetchGroupTree,
  })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = findGroup(groups ?? [], selectedId) ?? groups?.[0] ?? null
  const path = selected ? findPath(groups ?? [], selected.id) : []
  const { data: searchResults } = useQuery({
    queryKey: ['groups-search', selected?.displayName ?? ''],
    queryFn: () => searchGroups(selected!.displayName),
    enabled: selected != null,
  })
  const selectedSearch = searchResults?.find((result) => result.id === selected?.id)
  const displayPath = selectedSearch?.path ?? path

  return (
    <AppShell>
      <div className="mb-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">群組管理</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">群組與成員</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          管理公司、部門與協作團隊，群組會產生 `group:&lt;id&gt;` principal 供分享權限使用。
        </p>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      ) : !groups || groups.length === 0 ? (
        <div className="rounded-lg border border-border bg-card p-8 text-[13px] text-muted-foreground">
          目前尚未建立群組。
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
          <aside className="rounded-lg border border-border bg-card p-3">
            <div className="mb-2 px-1 text-[12px] font-semibold text-muted-foreground">群組樹</div>
            <GroupTree groups={groups} selectedId={selected?.id ?? null} onSelect={(group) => setSelectedId(group.id)} />
          </aside>
          {selected && (
            <GroupEditor
              group={selected}
              path={displayPath}
              memberCount={selectedSearch?.memberCount ?? 0}
            />
          )}
        </div>
      )}
    </AppShell>
  )
}

function findGroup(groups: GroupTreeNode[], id: string | null): GroupTreeNode | null {
  if (id == null) return null
  for (const group of groups) {
    if (group.id === id) return group
    const child = findGroup(group.children, id)
    if (child != null) return child
  }
  return null
}

function findPath(groups: GroupTreeNode[], id: string): string[] {
  for (const group of groups) {
    if (group.id === id) return [group.displayName]
    const childPath = findPath(group.children, id)
    if (childPath.length > 0) return [group.displayName, ...childPath]
  }
  return []
}
