# S098e3-T03: SkillDetail Flags tab — 「回報問題」CTA + FlagSubmitModal

## Spec
S098e3 — Flag Write Flow + Reviewer Queue（spec doc: `docs/grimo/specs/2026-05-03-S098e3-flag-write-flow.md`）

## BDD

**AC-9: 加「回報問題」CTA**
- Given：登入用戶開 SkillDetail 並切到 Flags tab
- When：tab 渲染後
- Then：tab 上方顯「回報問題」按鈕（不論有無既存 flag）；點擊開 FlagSubmitModal

**AC-10: FlagSubmitModal happy path**
- Given：modal 開啟
- When：選 type=「惡意指令」+ 寫 description「含後門」+ 點 Submit
- Then：發 POST flags；modal 關閉；既有 FlagsList 加新 row（refetch）；toast「已收到回報」（toast 可選）

## Implementation outline

### `frontend/src/components/FlagsList.tsx` (modify — S112-T03 ship)

加 hero CTA + 內嵌 FlagSubmitModal trigger。0 flags + N flags 兩 case 都顯 CTA。

```tsx
export function FlagsList({ skillId }: { skillId: string }) {
  const [showModal, setShowModal] = useState(false)
  const { data: flags } = useFlags(skillId)
  // ...

  return (
    <div>
      <div className="mb-4">
        <button
          onClick={() => setShowModal(true)}
          className="rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground"
        >
          回報問題
        </button>
      </div>
      {flags && flags.length === 0 && <EmptyState ... />}
      {flags && flags.length > 0 && <div>{flags.map(...)}</div>}
      {showModal && (
        <FlagSubmitModal
          skillId={skillId}
          onClose={() => setShowModal(false)}
        />
      )}
    </div>
  )
}
```

### `frontend/src/components/FlagSubmitModal.tsx` (new)

```tsx
export function FlagSubmitModal({ skillId, onClose }: { skillId: string; onClose: () => void }) {
  const [type, setType] = useState<Flag['type']>('malicious')
  const [description, setDescription] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => createFlag(skillId, { type, description: description.trim() || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-flags', skillId] })
      queryClient.invalidateQueries({ queryKey: ['me-flags-summary'] })
      onClose()
    },
  })

  // 6 個 type radio group + textarea + Submit/Cancel
  // type label 用 FLAG_TYPE_LABEL 對照
}
```

設計：
- type 走 6 顆 radio button（per spec §1 visual flow），label 用既有 `FLAG_TYPE_LABEL`
- description optional（per backend nullable）；UI 不強制必填，提示「可選」
- Submit disabled 當 mutation pending
- onSuccess close modal + invalidate queries → 既有 FlagsList 自動 refetch

### Tests

`frontend/src/pages/SkillDetailPage.test.tsx` (modify) — 加 AC-9 / AC-10。

實際走 `FlagsList.test.tsx` extension（per S112-T03 啟示 isolated component test 比 page-level Tabs 可靠）。在既有 FlagsList.test.tsx 加：
- AC-9: 「回報問題」CTA 渲染
- AC-10: 點 CTA → modal 開 → 選 type + 填 desc + Submit → POST 觸發

## Target Files

- `frontend/src/components/FlagsList.tsx` (modify — 加 CTA + modal trigger)
- `frontend/src/components/FlagSubmitModal.tsx` (new — type radio + textarea + Submit)
- `frontend/src/components/FlagsList.test.tsx` (modify — 加 AC-9/AC-10 isolation tests)

## Depends On
T02（需要 createFlag from api/flags.ts；FLAG_TYPE_LABEL 既有 S112-T02 ship）

## Status
✅ shipped 2026-05-03 cron Tick 15

## Result

實作 per spec template — 新建 `FlagSubmitModal` 獨立 component (per S112-T03 啟示，避開 inline + Tab interaction 不可靠路徑)，FlagsList 加「回報問題」CTA 永顯（不論有無既存 flag）。

**Verification**：
- `FlagsList.test.tsx` 4/4 PASS @ 1.78s（既有 AC-1/AC-2 + 新 AC-9/AC-10）
- typecheck 0 error

**Files changed**：
- `frontend/src/components/FlagSubmitModal.tsx` (new — 6 type radio + description optional textarea + useMutation 呼叫 createFlag + onSuccess invalidate ['skill-flags'] + ['me-flags-summary'])
- `frontend/src/components/FlagsList.tsx` (modify — 加 useState showModal + CTA button + FlagSubmitModal trigger；CTA 上方一律顯，0 flag 時 EmptyState 文案調整為「使用上方按鈕送出回報」)
- `frontend/src/components/FlagsList.test.tsx` (modify — 加 fireEvent import + AC-9 / AC-10 兩 test)
