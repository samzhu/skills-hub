# S096g2-T03: Frontend infra — api/skills.ts request mutations + useRequests hook

## Spec
S096g2 — Request Board Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096g2-request-board-full.md`）

## BDD（infra task — 無直接 AC，T04 verification 涵蓋）

- Given：T01/T02 backend endpoints 規格已知
- When：T04 import `createRequest` / `useRequests` / `toggleVote` 等 helper
- Then：型別正確 + hooks 正常運作

## Implementation outline

### `frontend/src/api/skills.ts` (modify — 既有有 SkillRequest type)

加 7 個 mutation/query helpers：

```typescript
// 既有 SkillRequest type 升級欄位（per backend Request DTO）：
//   - 加 'vote_count: number' / 'voteCount: number'（對齊 backend snake/camel）
//   - 加 'fulfilledSkillId: string | null'
//   - 確認 status union 'OPEN'|'IN_PROGRESS'|'FULFILLED'

export interface CreateRequestBody { title: string; description: string }

export function createRequest(body: CreateRequestBody): Promise<{ id: string }> { /* POST /requests */ }

export function fetchRequest(id: string): Promise<SkillRequest> { /* GET /requests/{id} */ }

export interface VoteResult { voted: boolean; voteCount: number }
export function toggleVote(requestId: string): Promise<VoteResult> { /* POST /requests/{id}/vote */ }

export interface ClaimResult { claimer: string; status: 'IN_PROGRESS' }
export function claimRequest(requestId: string): Promise<ClaimResult> { /* POST /requests/{id}/claim */ }

export function releaseClaim(requestId: string): Promise<void> { /* DELETE /requests/{id}/claim */ }

export interface FulfillResult { status: 'FULFILLED'; fulfilledSkillId: string }
export function fulfillRequest(requestId: string, skillId: string): Promise<FulfillResult> {
  /* POST /requests/{id}/fulfill body {skillId} */
}

export function deleteRequest(requestId: string): Promise<void> { /* DELETE /requests/{id} */ }
```

### `frontend/src/hooks/useRequests.ts` (new)

```typescript
export function useRequests(opts?: { sort?: 'votes' | 'created'; status?: SkillRequest['status'] }) {
  const params = new URLSearchParams()
  if (opts?.sort) params.set('sort', opts.sort)
  if (opts?.status) params.set('status', opts.status)
  const qs = params.toString() ? `?${params}` : ''
  return useQuery({
    queryKey: ['requests', opts?.sort ?? 'votes', opts?.status ?? 'all'],
    queryFn: () => apiFetch<SkillRequest[]>(`/requests${qs}`),
    staleTime: 30 * 1000,
  })
}
```

### `frontend/src/hooks/useRequest.ts` (new)

Single detail：對齊 `useSkill` 既有 pattern。

## Target Files

- `frontend/src/api/skills.ts` (modify — 加 7 functions + extend SkillRequest fields)
- `frontend/src/hooks/useRequests.ts` (new)
- `frontend/src/hooks/useRequest.ts` (new)

## Depends On
T01, T02（backend endpoints 需 ship；frontend type 同步可早）

## Status
✅ completed — 2026-05-03

**Verification**：
- `npx tsc --noEmit` PASS（無錯誤）
- `npx vitest run src/hooks/useSkill.test.tsx` 5/5 PASS @ 1.34s（adjacent hook smoke 確認 React Query infra 不破）

**Files changed**：
- `frontend/src/api/skills.ts` (modify — 重寫 SkillRequest 對齊 backend RequestResponse；fetchRequests 加 sort/status opts；新增 fetchRequest / createRequest / toggleVote / claimRequest / releaseClaim / fulfillRequest / deleteRequest 共 7 個 helper + VoteResult / ClaimResult / FulfillResult / CreateRequestBody / RequestsQuery 5 個 type)
- `frontend/src/hooks/useRequests.ts` (new — list with sort + status filter；30s cache + refetchOnWindowFocus 對齊 useFlagsQueue)
- `frontend/src/hooks/useRequest.ts` (new — single detail；對齊 useSkill pattern)
- `frontend/src/pages/RequestBoardPage.tsx` (modify 1 line — `req.votes` → `req.voteCount` 對齊新 type；T04 會做完整 page 重建)
