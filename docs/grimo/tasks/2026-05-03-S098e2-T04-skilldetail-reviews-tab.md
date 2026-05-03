# S098e2-T04: SkillDetailPage Reviews tab — ReviewsPanel + RatingHero + ReviewForm + tests

## Spec
S098e2 — Reviews Aggregate + Ratings + SkillDetail Reviews tab（spec doc: `docs/grimo/specs/2026-05-03-S098e2-reviews-aggregate.md`）

## BDD

**AC-10: 0 reviews 顯示 invite EmptyState**
- Given：skill `S` 無評論
- When：user 切到 Reviews tab
- Then：顯 `EmptyState tone="invite"` headline「成為第一個評論這個技能的人」+「撰寫評論」CTA

**AC-11: N reviews 顯示 hero + list**
- Given：skill `S` 有 3 則 review (avg 4.0)
- When：切到 Reviews tab
- Then：上方「⭐ 4.0 · 3 則評論」hero；下方時序 desc list；每 row 含星等 + content + 作者 + 日期 + 自己 review 加編輯/刪除按鈕

**AC-12: 提交 review 表單 happy path**
- Given：alice 登入；skill `S` 已存在；alice 從未評論該 skill
- When：點「撰寫評論」→ modal 開 → 選 5 星 → 輸入「Great」→ Submit
- Then：modal 關閉；list 內出現 alice 新 review；hero 平均更新；CTA 變「編輯我的評論」

## Implementation outline

### Decision: extract to standalone component

Per S112-T03 啟示（Radix Tabs JSDOM fireEvent.click 不可靠）：
**新建 `frontend/src/components/ReviewsPanel.tsx`** 而非 inline internal component。Single responsibility + isolation testing。

### 新檔 `frontend/src/components/ReviewsPanel.tsx`

```tsx
export function ReviewsPanel({ skill, currentUserId }: { skill: Skill; currentUserId?: string }) {
  const { data: reviews } = useReviews(skill.id)
  const [showForm, setShowForm] = useState(false)
  const myReview = reviews?.find(r => r.authorId === currentUserId)

  if (!reviews) return <div>載入中...</div>

  if (reviews.length === 0) {
    return (
      <EmptyState
        tone="invite"
        headline="成為第一個評論這個技能的人"
        sub="..."
        primaryAction={{ label: '撰寫評論', onClick: () => setShowForm(true) }}
      />
      {/* 同檔 ReviewForm modal */}
    )
  }

  return (
    <div>
      <RatingHero average={skill.averageRating} count={skill.reviewCount} />
      {!myReview && <button onClick={() => setShowForm(true)}>撰寫評論</button>}
      {myReview && <button onClick={() => setShowForm(true)}>編輯我的評論</button>}
      <div className="space-y-3">
        {reviews.map(r => <ReviewRow key={r.id} review={r} isMine={r.authorId === currentUserId} />)}
      </div>
      {showForm && <ReviewForm skillId={skill.id} initial={myReview} onClose={() => setShowForm(false)} />}
    </div>
  )
}
```

### Internal components

- `RatingHero({ average, count })` — `⭐ {average.toFixed(1)} · {count} 則評論`
- `ReviewRow({ review, isMine })` — 星等 + content + author + date + 自己 review 加編輯/刪除 button
- `ReviewForm({ skillId, initial, onClose })` — `<RatingStars interactive />` + `<textarea>` + Submit button；用 `useMutation(createReview)`

### `SkillDetailPage` 改寫 Reviews tab

```tsx
// 替換 line 213-219
<TabsContent value="reviews" className="mt-4">
  <ReviewsPanel skill={skill} currentUserId={me?.sub} />
</TabsContent>
```

加 imports: `ReviewsPanel`, `useMe`。

### Tests — `frontend/src/components/ReviewsPanel.test.tsx`

```tsx
// AC-10: 0 reviews 顯示 EmptyState invite
// AC-11: 3 reviews 顯示 RatingHero + list
// AC-12: 點「撰寫評論」→ form 出現 → 選 star → Submit → fetch 對 POST 觸發
```

URL-aware fetch mock pattern（per HomePage.test.tsx）。

## Verify

- `cd frontend && npx vitest run src/components/ReviewsPanel.test.tsx` → AC-10/AC-11/AC-12 PASS
- 既有 SkillDetailPage error path tests 不掉
- `npx tsc --noEmit` 0 error

## Target Files

- `frontend/src/components/ReviewsPanel.tsx` (new — extracted from SkillDetailPage; mirror S112-T03 FlagsList pattern)
- `frontend/src/components/ReviewsPanel.test.tsx` (new — AC-10/AC-11/AC-12)
- `frontend/src/pages/SkillDetailPage.tsx` (modify — Reviews tab `<EmptyState>` → `<ReviewsPanel skill={skill} currentUserId={me?.sub}>`；加 useMe import)

## Depends On
- T01（後端 `/reviews` POST/GET/DELETE endpoints）
- T02（Skill DTO 含 averageRating/reviewCount）
- T03（api/reviews.ts + useReviews + RatingStars + Skill type field）

## Status
pending
