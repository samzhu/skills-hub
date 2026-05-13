import type { GroupTreeNode } from '@/api/groups'

interface GroupTreeProps {
  groups: GroupTreeNode[]
  selectedId: string | null
  onSelect: (group: GroupTreeNode) => void
}

const kindLabels: Record<GroupTreeNode['kind'], string> = {
  COMPANY: '公司',
  DEPARTMENT: '部門',
  TEAM: '團隊',
  OTHER: '其他',
}

/**
 * S170 — renders the organization group tree with keyboard-readable treeitem buttons.
 */
export function GroupTree({ groups, selectedId, onSelect }: GroupTreeProps) {
  return (
    <div role="tree" aria-label="群組樹" className="space-y-1">
      {groups.map((group) => (
        <GroupTreeItem key={group.id} group={group} selectedId={selectedId} onSelect={onSelect} depth={0} />
      ))}
    </div>
  )
}

function GroupTreeItem({
  group,
  selectedId,
  onSelect,
  depth,
}: GroupTreeProps & { group: GroupTreeNode; depth: number }) {
  const selected = group.id === selectedId
  return (
    <div>
      <button
        type="button"
        role="treeitem"
        aria-selected={selected}
        onClick={() => onSelect(group)}
        className={`flex w-full items-center justify-between rounded-md px-3 py-2 text-left text-[13px] transition-colors ${
          selected ? 'bg-primary text-primary-foreground' : 'text-foreground hover:bg-muted'
        }`}
        style={{ paddingLeft: `${12 + depth * 18}px` }}
      >
        <span className="truncate">{group.displayName}</span>
        <span className={`ml-2 text-[11px] ${selected ? 'text-primary-foreground/80' : 'text-muted-foreground'}`}>
          {kindLabels[group.kind]}
        </span>
      </button>
      {group.children.length > 0 && (
        <div className="mt-1 space-y-1">
          {group.children.map((child) => (
            <GroupTreeItem key={child.id} group={child} selectedId={selectedId} onSelect={onSelect} depth={depth + 1} groups={[]} />
          ))}
        </div>
      )}
    </div>
  )
}
