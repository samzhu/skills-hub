# S112-T02: Frontend infra — `api/flags.ts` + `lib/flag-labels.ts`

## Spec
S112 — Flag wiring full-stack（spec doc: `docs/grimo/specs/2026-05-03-S112-flag-wiring-full-stack.md`）

## BDD（infra task — 無直接 AC，T03/T04 verification 涵蓋）

- Given：T01 backend `/api/v1/me/flags-summary` 規格已知（即使尚未 ship 也可以先建 type）
- When：T03/T04 import 這些 hook + 中譯 const
- Then：型別正確（`Flag` enum 對齊後端 `ALLOWED_TYPES`）+ 中譯齊全（type 6 個、status 至少 OPEN 一個）

## Implementation outline

### 新檔 `frontend/src/api/flags.ts`

```typescript
import { apiFetch } from './client'

export interface Flag {
  id: string
  skillId: string
  type: 'malicious' | 'spam' | 'inappropriate' | 'copyright' | 'security' | 'other'
  description: string | null
  reportedBy: string
  createdAt: string
  status: 'OPEN' | 'RESOLVED'
}

export function fetchFlags(skillId: string): Promise<Flag[]> {
  return apiFetch<Flag[]>(`/skills/${skillId}/flags`)
}

export interface FlagsSummary {
  openCount: number
}

export function fetchFlagsSummary(): Promise<FlagsSummary> {
  return apiFetch<FlagsSummary>('/me/flags-summary')
}
```

### 新檔 `frontend/src/lib/flag-labels.ts`

```typescript
import type { Flag } from '../api/flags'

export const FLAG_TYPE_LABEL: Record<Flag['type'], string> = {
  malicious: '惡意指令',
  spam: '垃圾內容',
  inappropriate: '不當內容',
  copyright: '版權問題',
  security: '資安疑慮',
  other: '其他',
}

export const FLAG_STATUS_LABEL: Record<Flag['status'], string> = {
  OPEN: '待處理',
  RESOLVED: '已處理',
}

// 對齊 SkillDetailPage.tsx:33-37 STATUS_PILL_STYLE pattern (semantic-soft palette)
export const FLAG_STATUS_STYLE: Record<Flag['status'], { backgroundColor: string; color: string }> = {
  OPEN:     { backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' },  // warning-soft
  RESOLVED: { backgroundColor: 'rgba(29,158,117,0.14)', color: '#6FD8B0' },  // success-soft
}
```

## Verify

- `cd frontend && npm run typecheck`（既有 npm script；無則 `npx tsc --noEmit`）→ 0 error
- `cd frontend && npm test`（既有 vitest suite）→ 既有 passes 不掉

## Target Files

- `frontend/src/api/flags.ts` (new)
- `frontend/src/lib/flag-labels.ts` (new)

## Depends On
none

## Status
✅ shipped 2026-05-03 cron Tick 4

## Result

純 type-only / const-only infra：2 個新檔（`api/flags.ts` 39 LOC + `lib/flag-labels.ts` 28 LOC）。`Flag.type` enum 對齊後端 `FlagService.ALLOWED_TYPES`（S072 6 個）；`status` 含 OPEN + RESOLVED（OPEN 為 backend 既有，RESOLVED 預留 S098e3 reviewer 流程）。

**Verification**：
- `cd frontend && npx tsc --noEmit` → 0 error
- vitest suite 不跑（infra 無 callsite，既有 tests 不會 import 新檔）

**T03/T04 後續**：
- T03 將 import `fetchFlags` + 中譯 const 渲染 SkillDetail Flags tab；自建 `useFlags` hook 在 page 內或新 `hooks/useFlags.ts`
- T04 將 import `fetchFlagsSummary` 給 MySkillsPage MetricCard；自建 `useFlagsSummary` hook 同上
