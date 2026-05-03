# S096g2-T04: RequestBoardPage CTA + CreateRequestModal + VoteButton + RequestActionBar + tests

## Spec
S096g2 — Request Board Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096g2-request-board-full.md`）

## BDD

**AC-15: CTA 啟用 + 真資料**
- Given：DB 內有 3 筆 requests
- When：user 開啟 `/requests`
- Then：「發起新需求」按鈕**不再 disabled**；row list 渲染 3 筆，按 votes desc

**AC-16: Create modal happy path**
- Given：alice 登入
- When：alice 點「發起新需求」→ modal 開 → 填 title + description → Submit
- Then：modal 關閉；列表新增 row

**AC-17: Vote button toggle**
- Given：r1 顯示 vote_count=5
- When：alice 點 ↑ vote 按鈕
- Then：發 POST vote；count 樂觀更新為 6；按鈕變「已投票」style；再點 → 5、按鈕復原

## Implementation outline

### `frontend/src/components/CreateRequestModal.tsx` (new)

mirror FlagSubmitModal 既有 pattern：title input + description textarea + useMutation(createRequest) + onSuccess invalidate ['requests'] + onClose.

### `frontend/src/components/VoteButton.tsx` (new)

```tsx
export function VoteButton({ requestId, voteCount, voted }: { requestId: string; voteCount: number; voted: boolean }) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: () => toggleVote(requestId),
    // 樂觀更新：暫先 +1 / -1，server response 確認後再校正
    onMutate: () => { /* setQueryData optimistic */ },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['requests'] }),
  })

  return (
    <button onClick={() => mutation.mutate()} disabled={mutation.isPending}>
      <ArrowUp /> {voteCount}
    </button>
  )
}
```

注意：`voted` flag 從 server 回 — MVP 暫不傳（無「我 vote 過哪些」endpoint per spec §2.4 trim）；button 樣式由 mutation result 提供，重新整理頁面失去 voted state，可接受 trim。

### `frontend/src/components/RequestActionBar.tsx` (new)

state-aware buttons：
- OPEN + 我非 requester → 「認領」button (POST claim)
- OPEN + 我是 requester → 「刪除」button (DELETE，with confirm prompt)
- IN_PROGRESS + 我是 claimer → 「釋放」button (DELETE claim) + 「上架完成」button (POST fulfill — 開 skill picker dropdown)
- FULFILLED + fulfilledSkillId → 「查看技能」link to `/skills/{id}`
- 否則 → 無 action

### `frontend/src/pages/RequestBoardPage.tsx` (modify — 既有 stub)

CTA enabled + 串 `useRequests` + sort chips（votes / created）+ status filter chips + 串 VoteButton + RequestActionBar。
既有 EmptyState 改 condition：`requests.length === 0` 才顯。

### Tests `RequestBoardPage.test.tsx` (new)

URL-aware fetch mock，驗：
- AC-15: 3 requests → 3 row + CTA 不 disabled + 順序 votes desc
- AC-16: 點 CTA → modal 開 → fill+submit → POST 觸發
- AC-17: 點 vote → POST toggle 觸發

## Target Files

- `frontend/src/components/CreateRequestModal.tsx` (new)
- `frontend/src/components/VoteButton.tsx` (new)
- `frontend/src/components/RequestActionBar.tsx` (new)
- `frontend/src/pages/RequestBoardPage.tsx` (modify — 既有 stub 加實際 wire-up)
- `frontend/src/pages/RequestBoardPage.test.tsx` (new — AC-15/16/17)

## Depends On
- T01 + T02（backend endpoints）
- T03（api helpers + hooks）

## Status
pending
