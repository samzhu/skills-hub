import { searchGroups } from './groups'

export type ShareTargetType = 'user' | 'group' | 'public'

export interface ShareTarget {
  type: ShareTargetType
  principalId: string
  label: string
  hint: string
}

export async function searchShareTargets(query: string): Promise<ShareTarget[]> {
  const q = query.trim()
  if (!q) return []

  const groups = await searchGroups(q)
  return groups.map((g) => ({
    type: 'group',
    principalId: g.id,
    label: g.displayName,
    hint: g.path.join(' / ') || g.kind,
  }))
}
