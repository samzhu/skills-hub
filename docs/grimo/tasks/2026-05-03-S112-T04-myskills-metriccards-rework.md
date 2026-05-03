# S112-T04: MySkillsPage MetricCards rework — `useFlagsSummary` + page edit + tests

## Spec
S112 — Flag wiring full-stack（spec doc: `docs/grimo/specs/2026-05-03-S112-flag-wiring-full-stack.md`）

## BDD

**AC-3 — 待處理回報 MetricCard 顯示後端 openCount**
- Given：user A 有 3 個 PUBLISHED skill；其中 2 個 skill 各被 flag 1 次（共 2 個 OPEN flag）
- When：user A 開啟 `/my-skills`
- Then：「待處理回報」MetricCard `value` 顯示 `2`（subtitle「未處理 OPEN 狀態」）

**AC-4 — 移除「平均評分」MetricCard**
- Given：MySkillsPage 渲染後
- When：query DOM 找 `label="平均評分"` 的 MetricCard
- Then：找不到該卡；4-card grid 變 3-card（技能總數 / 下載總數 / 待處理回報）；grid class `lg:grid-cols-4` → `lg:grid-cols-3`

## Implementation outline

### 新檔 `frontend/src/hooks/useFlagsSummary.ts`

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchFlagsSummary, type FlagsSummary } from '../api/flags'

export function useFlagsSummary(enabled: boolean = true) {
  return useQuery<FlagsSummary>({
    queryKey: ['me-flags-summary'],
    queryFn: fetchFlagsSummary,
    enabled,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
```

### 修改 `frontend/src/pages/MySkillsPage.tsx`

替換現行 line 86-99（4-card grid + 兩張寫死 MetricCard）：

```tsx
// 新增：在現有 metric calc 區塊（line 56-60）之後
const { data: flagsSummary } = useFlagsSummary(total > 0)  // 0 skill 不必查

// 替換 4-card grid 為 3-card
<div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
  {/* 移除「平均評分」MetricCard — 等 S101a Quality Score */}
  <MetricCard
    label="技能總數"
    value={total}
    subtitle={`已發布 ${published} · 草稿 ${drafts} · 已停用 ${suspended}`}
  />
  <MetricCard
    label="下載總數"
    value={totalDownloads.toLocaleString()}
    subtitle="累積下載"
  />
  <MetricCard
    label="待處理回報"
    value={flagsSummary?.openCount ?? 0}
    subtitle="未處理 OPEN 狀態"
  />
</div>
```

加 import `useFlagsSummary` from `@/hooks/useFlagsSummary`。

### 修改 `frontend/src/pages/MySkillsPage.test.tsx`

加 2 個 test：

```tsx
it('AC-3: 待處理回報 MetricCard 顯示 useFlagsSummary openCount', async () => {
  // mock useMe → { sub: 'alice', ... }
  // mock useSkillList → 3 PUBLISHED
  // mock fetchFlagsSummary → { openCount: 2 }
  // render；expect MetricCard label="待處理回報" 包含文字 "2"
})

it('AC-4: 「平均評分」MetricCard 不存在；grid 為 3-card', async () => {
  // mock 同上
  // render；expect queryByText(/平均評分/) toBeNull
  // expect grid 內 MetricCard count = 3（或 3 個 label：技能總數 / 下載總數 / 待處理回報）
})
```

對齊既有 MySkillsPage.test.tsx 既有 mock pattern（useMe / useSkillList）。

## Verify

- `cd frontend && npm test -- MySkillsPage` → AC-3 + AC-4 PASS
- 既有 MySkillsPage tests 不掉（特別是 hero / tab / SkillRow 系列）
- `npm run typecheck` 0 error

## Target Files

- `frontend/src/hooks/useFlagsSummary.ts` (new)
- `frontend/src/pages/MySkillsPage.tsx` (modify — 加 useFlagsSummary + 移除「平均評分」+ 接「待處理回報」+ grid 3-col)
- `frontend/src/pages/MySkillsPage.test.tsx` (modify — 加 AC-3 / AC-4)

## Depends On
- T01（後端 `/me/flags-summary` 需要 ship 才能跑 manual smoke；test 走 mock 不阻塞）
- T02（需要 `api/flags.ts` 的 `fetchFlagsSummary` / `FlagsSummary` type）

## Status
pending
