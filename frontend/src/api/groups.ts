import { apiFetch } from './client'

export type GroupKind = 'COMPANY' | 'DEPARTMENT' | 'TEAM' | 'OTHER'

export interface GroupTreeNode {
  id: string
  parentId: string | null
  kind: GroupKind
  displayName: string
  principalKey: string
  children: GroupTreeNode[]
}

export interface GroupSearchResult {
  id: string
  principalKey: string
  kind: GroupKind
  displayName: string
  path: string[]
  memberCount: number
}

export function fetchGroupTree(): Promise<GroupTreeNode[]> {
  return apiFetch<GroupTreeNode[]>('/groups/tree')
}

export function searchGroups(query: string): Promise<GroupSearchResult[]> {
  const params = new URLSearchParams({ q: query })
  return apiFetch<GroupSearchResult[]>(`/groups/search?${params}`)
}
