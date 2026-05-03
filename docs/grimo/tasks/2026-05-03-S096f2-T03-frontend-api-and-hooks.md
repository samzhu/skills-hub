# S096f2-T03: Frontend api/skills.ts 加 Collection helpers + 2 hooks

## Spec
S096f2 — Collections Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096f2-collections-full.md`）

## BDD（涵蓋的 AC — infrastructure for T04）

本 task 不直接驗任何 AC（純 frontend infra layer）；T04 的 AC-10/11/12 走本 task 提供的 helpers + hooks。

**AC-Smoke: typecheck + 7 helper signature 對齊 backend DTO**
- Given：本 task ship 後
- When：`npx tsc --noEmit` + `cd frontend && npm test`
- Then：tsc 0 errors；既有 collection-related test 仍 PASS（本 task 不引入 regression）

## Implementation outline

### `frontend/src/api/skills.ts` (modify — 加 3 helper + 2 type)

既有：`SkillCollection` interface + `fetchCollections()`。本 task 加：

```typescript
export interface CreateCollectionRequest {
  name: string
  description: string | null
  category: string
  skillIds: string[]
}

export function createCollection(body: CreateCollectionRequest): Promise<{ id: string }> {
  return apiFetch('/collections', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export interface CollectionSkillSummary {
  id: string
  name: string
  category: string
  riskLevel: string | null
  latestVersion: string | null
}

export interface CollectionDetail extends SkillCollection {
  ownerId: string
  installCount: number
  skills: CollectionSkillSummary[]
}

export function fetchCollection(id: string): Promise<CollectionDetail> {
  return apiFetch(`/collections/${id}`)
}

export function installCollection(id: string): Promise<{ downloadUrls: string[] }> {
  return apiFetch(`/collections/${id}/install`, { method: 'POST' })
}
```

對齊 backend `CollectionQueryController.CollectionDetail` + `CollectionCommandController.{CreateCollectionBody, InstallResponse}` shape。

### `frontend/src/hooks/useCollections.ts` (new — list with category filter)

既有 CollectionsPage 用 inline `useQuery` 跑 `fetchCollections`；本 task 抽出獨立 hook + 加 category param 對齊 backend API。

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchCollections, type SkillCollection } from '@/api/skills'

export function useCollections(category?: string) {
  return useQuery<SkillCollection[]>({
    queryKey: ['collections', category ?? 'all'],
    queryFn: () => fetchCollections(category),
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  })
}
```

**注意**：既有 `fetchCollections` 不接 query param — 本 task 順手 modify 加 optional `category` param（GET `/collections?category=Frontend`）。對齊 backend AC-5。

### `frontend/src/hooks/useCollection.ts` (new — single detail)

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchCollection, type CollectionDetail } from '@/api/skills'

export function useCollection(id: string | undefined) {
  return useQuery<CollectionDetail>({
    queryKey: ['collection', id],
    queryFn: () => fetchCollection(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}
```

對齊 useSkill / useRequest 既驗 pattern（id-keyed cache + enabled gate）。

### Caller 同步更新

既有 CollectionsPage.tsx 內 inline useQuery → 改用 useCollections() hook。修最小：

```typescript
// before
const { data: collections, isLoading } = useQuery({
  queryKey: ['collections'],
  queryFn: fetchCollections,
})

// after
const { data: collections, isLoading } = useCollections()
```

T04 將進一步加 category filter chip + Modal trigger；本 task 只做 hook extraction（minimal change）。

## Target Files

- `frontend/src/api/skills.ts` (modify — fetchCollections 加 optional `category` param + 加 createCollection / fetchCollection / installCollection + 2 type interface)
- `frontend/src/hooks/useCollections.ts` (new — list with optional category filter；對齊 useRequests pattern)
- `frontend/src/hooks/useCollection.ts` (new — single detail；對齊 useSkill / useRequest pattern)
- `frontend/src/pages/CollectionsPage.tsx` (modify — minimal: inline useQuery → useCollections())

## Depends On
- T01 + T02（backend collection endpoints ship — 本 task helpers 才能 typecheck against real shape）

## Status
pending
