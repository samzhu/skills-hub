# S098e3-T02: Frontend infra — `api/flags.ts` 補 mutations + `useFlagsQueue` hook

## Spec
S098e3 — Flag Write Flow + Reviewer Queue（spec doc: `docs/grimo/specs/2026-05-03-S098e3-flag-write-flow.md`）

## BDD（infra task — 無直接 AC，T03/T04 verification 涵蓋）

- Given：T01 backend POST/PATCH/GET cross-skill endpoints 規格已知
- When：T03 import `createFlag`、T04 import `useFlagsQueue` + `updateFlagStatus`
- Then：型別正確 + hook 正常運作

## Implementation outline

### `frontend/src/api/flags.ts` (modify — S112-T02 ship)

加 3 個 mutation/query helpers（既有檔有 fetchFlags / fetchFlagsSummary）：

```typescript
export interface CreateFlagBody {
  type: Flag['type']
  description?: string
}

export function createFlag(skillId: string, body: CreateFlagBody): Promise<{ id: string }> {
  return apiFetch<{ id: string }>(`/skills/${skillId}/flags`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function fetchFlagsByStatus(status?: Flag['status']): Promise<Flag[]> {
  const path = status ? `/flags?status=${status}` : '/flags'
  return apiFetch<Flag[]>(path)
}

export function updateFlagStatus(skillId: string, flagId: string, status: Flag['status']): Promise<void> {
  return apiFetch<void>(`/skills/${skillId}/flags/${flagId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}
```

注意 `Flag['status']` 已是 'OPEN' | 'RESOLVED'（S112-T02 既宣）；本 spec 加 'DISMISSED'  — 同步調整既有 type union（向下相容）。

### `frontend/src/hooks/useFlagsQueue.ts` (new)

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchFlagsByStatus, type Flag } from '../api/flags'

export function useFlagsQueue(status?: Flag['status']) {
  return useQuery<Flag[]>({
    queryKey: ['flags-queue', status ?? 'all'],
    queryFn: () => fetchFlagsByStatus(status),
    staleTime: 30 * 1000, // reviewer queue 期望較頻 refresh，30s
    refetchOnWindowFocus: true, // queue page 切回前景時 refetch
  })
}
```

### Type union update

`Flag['status']`: `'OPEN' | 'RESOLVED'` → `'OPEN' | 'RESOLVED' | 'DISMISSED'`
`FLAG_STATUS_LABEL` (`lib/flag-labels.ts`): 加 `DISMISSED: '已駁回'`
`FLAG_STATUS_STYLE`: 加 `DISMISSED: { backgroundColor: 'rgba(94,91,85,0.18)', color: '#A8A49C' }` (neutral-soft per DESIGN.md)

## Target Files

- `frontend/src/api/flags.ts` (modify)
- `frontend/src/lib/flag-labels.ts` (modify — 加 DISMISSED label/style)
- `frontend/src/hooks/useFlagsQueue.ts` (new)

## Depends On
T01（backend endpoints 需 ship 才能跑 manual smoke；test 走 mock 不阻塞）

## Status
✅ shipped 2026-05-03 cron Tick 14

## Result

純 type/hook + label const infra：3 個檔案 modify/new。

**Files changed**：
- `frontend/src/api/flags.ts` (modify) — Flag.status type union 加 'DISMISSED'；新 `CreateFlagBody` interface + `createFlag` / `fetchFlagsByStatus` / `updateFlagStatus` helpers
- `frontend/src/lib/flag-labels.ts` (modify) — FLAG_STATUS_LABEL 加 `DISMISSED: '已駁回'`；FLAG_STATUS_STYLE 加 neutral-soft palette (per DESIGN.md tertiary)
- `frontend/src/hooks/useFlagsQueue.ts` (new) — TanStack Query hook 30s staleTime + refetchOnWindowFocus（reviewer queue 期望 fresh）

**Verification**：
- typecheck 0 error
- FlagsList.test.tsx 2/2 regression PASS — Flag.status union 擴充向下相容（既有 OPEN test fixture 仍綠）
