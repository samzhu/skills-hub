# S112-T03: SkillDetail Flags tab — `useFlags` + page wiring + tests

## Spec
S112 — Flag wiring full-stack（spec doc: `docs/grimo/specs/2026-05-03-S112-flag-wiring-full-stack.md`）

## BDD

**AC-1 — 0 flags 顯示 EmptyState**
- Given：某 skill 無任何 flag（GET `/skills/{id}/flags` 回 `[]`）
- When：user 開啟 `/skills/:id` 並切到「回報」tab
- Then：tab 內顯示既有 `EmptyState`（`tone="clear"`，文案「目前沒有任何回報」）

**AC-2 — >0 flags 顯示 list with status pill**
- Given：某 skill 有 2 筆 flag（type="malicious" + type="spam"，各 status="OPEN"）
- When：user 開啟該 skill detail 並切到 Flags tab
- Then：顯示 2 row，每 row 含：
  - `type` pill（中譯：「惡意指令」/「垃圾內容」— 對齊 `FLAG_TYPE_LABEL`）
  - `description`（純文字；null 時不渲染或顯「—」）
  - `createdAt`（zh-TW 格式 `toLocaleDateString('zh-TW')`）
  - `status` pill（中譯「待處理」+ `FLAG_STATUS_STYLE.OPEN` 樣式）
- 排序：最新 createdAt 排最上（後端已 ORDER BY desc，前端不再排）

## Implementation outline

### 新檔 `frontend/src/hooks/useFlags.ts`

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchFlags, type Flag } from '../api/flags'

export function useFlags(skillId: string | undefined) {
  return useQuery<Flag[]>({
    queryKey: ['skill-flags', skillId],
    queryFn: () => fetchFlags(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
```

對齊 `useSkillStats.ts` 既有 pattern（同 staleTime / 同 enabled gate / 同 queryKey shape）。

### 修改 `frontend/src/pages/SkillDetailPage.tsx`

替換現行 line 220-227（Flags tab `<EmptyState>` 直接 render）：

```tsx
<TabsContent value="flags" className="mt-4">
  <FlagsList skillId={id ?? ''} />
</TabsContent>
```

同檔內加新 internal component：

```tsx
function FlagsList({ skillId }: { skillId: string }) {
  const { data: flags, isLoading } = useFlags(skillId)
  if (isLoading) return <div className="py-8 text-sm text-muted-foreground">載入中...</div>
  if (!flags || flags.length === 0) {
    return (
      <EmptyState
        tone="clear"
        headline="目前沒有任何回報"
        sub="若你發現此技能含惡意指令、誤導 description 或其他問題，回報功能即將推出，可送至審核佇列由 reviewer 處理。"
      />
    )
  }
  return (
    <div className="space-y-2">
      {flags.map((f) => <FlagRow key={f.id} flag={f} />)}
    </div>
  )
}

function FlagRow({ flag }: { flag: Flag }) {
  return (
    <div className="rounded-md border p-3">
      <div className="flex items-center gap-2">
        <span className="rounded px-2 py-0.5 text-[11px] bg-secondary text-foreground/80">
          {FLAG_TYPE_LABEL[flag.type]}
        </span>
        <span
          className="rounded-full px-2 py-0.5 text-[11px] font-medium"
          style={FLAG_STATUS_STYLE[flag.status]}
        >
          {FLAG_STATUS_LABEL[flag.status]}
        </span>
        <span className="ml-auto text-[11px] text-muted-foreground">
          {new Date(flag.createdAt).toLocaleDateString('zh-TW')}
        </span>
      </div>
      {flag.description && (
        <p className="mt-2 text-[13px] text-muted-foreground">{flag.description}</p>
      )}
    </div>
  )
}
```

加 imports：`useFlags` from `@/hooks/useFlags`、`Flag` from `@/api/flags`、`FLAG_TYPE_LABEL/FLAG_STATUS_LABEL/FLAG_STATUS_STYLE` from `@/lib/flag-labels`。

### 修改 `frontend/src/pages/SkillDetailPage.test.tsx`

加 2 個 test：

```tsx
it('AC-1: Flags tab 顯示 EmptyState 當 0 flags', async () => {
  // mock fetchFlags → []
  // render；切到 Flags tab；expect text「目前沒有任何回報」
})

it('AC-2: Flags tab 渲染 list with type/status pill 當 >0 flags', async () => {
  // mock fetchFlags → [{type:'malicious', status:'OPEN', ...}, {type:'spam', status:'OPEN', ...}]
  // render；切到 Flags tab；expect 2 rows + 「惡意指令」/「垃圾內容」/「待處理」x2
})
```

對齊既有測試 pattern（既有 SkillDetailPage.test.tsx 內 AC-N 樣板）。

## Verify

- `cd frontend && npm test -- SkillDetailPage` → AC-1 + AC-2 PASS
- 既有 SkillDetailPage tests 不掉
- `npm run typecheck` 0 error

## Target Files

- `frontend/src/hooks/useFlags.ts` (new)
- `frontend/src/pages/SkillDetailPage.tsx` (modify — Flags tab + 2 internal components)
- `frontend/src/pages/SkillDetailPage.test.tsx` (modify — 加 AC-1 / AC-2)

## Depends On
T02（需要 `api/flags.ts` 的 `Flag` type / `fetchFlags` + `lib/flag-labels.ts` 中譯表）

## Status
pending
