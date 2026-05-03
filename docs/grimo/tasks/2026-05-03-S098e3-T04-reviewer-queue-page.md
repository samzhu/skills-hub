# S098e3-T04: Reviewer queue page `/flags` + AppShell nav + tests

## Spec
S098e3 — Flag Write Flow + Reviewer Queue（spec doc: `docs/grimo/specs/2026-05-03-S098e3-flag-write-flow.md`）

## BDD

**AC-11: `/flags` reviewer queue page list OPEN flags**
- Given：3 flags 不同 skill 都 OPEN
- When：user 開啟 `/flags`
- Then：頁面顯 3 row，每 row 含 type pill + description + 對應 skill name (link 跳 SkillDetail) + reporter + createdAt + 「Resolve」+「Dismiss」按鈕

**AC-12: Resolve action**
- Given：reviewer queue 列出 1 筆 OPEN flag
- When：user 點該 row 的「Resolve」按鈕
- Then：發 PATCH status=RESOLVED；row 從 OPEN 列表消失（filter 過濾）；toast「已處理 1 筆回報」（toast 可選）

**AC-13: AppShell nav 加「待審回報」入口**
- Given：AppShell 渲染後
- When：query nav links
- Then：含「待審回報」link target = `/flags`，highlights when on `/flags`

## Implementation outline

### `frontend/src/pages/FlagsQueuePage.tsx` (new)

```tsx
export function FlagsQueuePage() {
  const { data: flags } = useFlagsQueue('OPEN')
  const queryClient = useQueryClient()

  const updateMutation = useMutation({
    mutationFn: ({ skillId, flagId, status }: { skillId: string; flagId: string; status: Flag['status'] }) =>
      updateFlagStatus(skillId, flagId, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['flags-queue'] }),
  })

  return (
    <AppShell>
      <h1 className="text-[22px] font-semibold mb-4">待審回報</h1>
      {/* 列表：每 row type pill + description + Link to /skills/{skillId} + reporter + createdAt + Resolve/Dismiss buttons */}
    </AppShell>
  )
}
```

需 fetch skill name 對應每 flag 的 skillId — option：
- (a) Backend list endpoint JOIN skills 回 skillName（spec §4.1 cross-skill list 暫沒寫，需確認）— 若沒，前端 fallback
- (b) 前端 useQueries fetch skill detail per flag — N+1，可接受 for MVP（5-10 OPEN flags）
- (c) 顯示 skillId 而非 name + link 到 detail page

選 (c) 為 MVP simplest — link target `/skills/{skillId}`，user click 後看到 name。如需 name 上市再 polish backend。

### `frontend/src/components/AppShell.tsx` (modify)

既有 nav link 結構加一條：

```tsx
<NavLink to="/flags">待審回報</NavLink>
```

需確認 AppShell 既有 nav 結構樣式 — read 既有 callsite 對齊。

### `frontend/src/App.tsx` (modify)

加 route：

```tsx
<Route path="/flags" element={<FlagsQueuePage />} />
```

### Tests — `frontend/src/pages/FlagsQueuePage.test.tsx` (new)

```tsx
// AC-11: render with 3 OPEN flags → 3 rows + Resolve/Dismiss buttons
// AC-12: click Resolve → PATCH 觸發 + invalidate query
```

URL-aware fetch mock per HomePage.test.tsx pattern。

AC-13 (AppShell nav) 視為 visual regression — 暫不寫 unit test（既有 AppShell.test.tsx 若有再補；defer）。

## Target Files

- `frontend/src/pages/FlagsQueuePage.tsx` (new)
- `frontend/src/pages/FlagsQueuePage.test.tsx` (new — AC-11/AC-12)
- `frontend/src/components/AppShell.tsx` (modify — 加 nav link)
- `frontend/src/App.tsx` (modify — 加 /flags route)

## Depends On
- T01（backend GET /flags + PATCH endpoint）
- T02（useFlagsQueue + updateFlagStatus）

## Status
pending
