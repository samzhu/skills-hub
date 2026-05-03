# S098e2-T03: Frontend infra — `api/reviews.ts` + `useReviews` + `RatingStars` + Skill type field

## Spec
S098e2 — Reviews Aggregate + Ratings + SkillDetail Reviews tab（spec doc: `docs/grimo/specs/2026-05-03-S098e2-reviews-aggregate.md`）

## BDD（infra task — 無直接 AC，T04 verification 涵蓋）

- Given：T01/T02 backend `/skills/{id}/reviews` 規格已知 + Skill DTO 含 averageRating / reviewCount
- When：T04 import 這些 hook + RatingStars
- Then：型別正確 + RatingStars readonly/interactive 兩 mode 都渲染

## Implementation outline

### 新檔 `frontend/src/api/reviews.ts`

```typescript
import { apiFetch } from './client'

export interface Review {
  id: string
  skillId: string
  authorId: string
  rating: number       // 1-5
  content: string
  createdAt: string
  updatedAt: string
}

export function fetchReviews(skillId: string): Promise<Review[]> {
  return apiFetch<Review[]>(`/skills/${skillId}/reviews`)
}

export interface CreateReviewBody {
  rating: number
  content: string
}

export function createReview(skillId: string, body: CreateReviewBody): Promise<{ id: string }> {
  return apiFetch<{ id: string }>(`/skills/${skillId}/reviews`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteReview(skillId: string, reviewId: string): Promise<void> {
  return apiFetch<void>(`/skills/${skillId}/reviews/${reviewId}`, { method: 'DELETE' })
}
```

### 新檔 `frontend/src/hooks/useReviews.ts`

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchReviews, type Review } from '../api/reviews'

export function useReviews(skillId: string | undefined) {
  return useQuery<Review[]>({
    queryKey: ['skill-reviews', skillId],
    queryFn: () => fetchReviews(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
```

對齊 `useFlags` / `useSkillStats` 既有 pattern。

### 新檔 `frontend/src/components/RatingStars.tsx`

5-star icon row，readonly 模式（顯示 average 1-5）+ interactive 模式（picker）。Lucide `Star` icon；filled vs empty 由 prop 控制。

```typescript
interface RatingStarsProps {
  value: number              // 0-5（小數允許 readonly 模式半星顯示）
  onChange?: (v: number) => void  // 給時為 interactive
  size?: number              // px, default 16
}
```

設計：interactive 模式 1-5 按鈕；readonly 顯示 fill 比例；半星 (e.g., 4.3) readonly 用 inline mask 或近似 4.5 round。

### Skill type 加欄位

```typescript
// frontend/src/types/skill.ts
export interface Skill {
  // ... 既有欄位
  averageRating: number
  reviewCount: number
}
```

加 averageRating / reviewCount field 對齊 backend Skill DTO（T02 加欄位）。

### Tests

- `RatingStars.test.tsx` — 簡 unit test：readonly 模式顯 N 顆 fill star；interactive 模式 click 觸發 onChange

## Verify

- `cd frontend && npx tsc --noEmit` → 0 error
- `npx vitest run RatingStars` → PASS

## Target Files

- `frontend/src/api/reviews.ts` (new)
- `frontend/src/hooks/useReviews.ts` (new)
- `frontend/src/components/RatingStars.tsx` (new)
- `frontend/src/components/RatingStars.test.tsx` (new)
- `frontend/src/types/skill.ts` (modify — 加 averageRating/reviewCount)

## Depends On
- T02（Skill DTO 加欄位需要 backend ship 才有真資料；frontend type 同步可早）

## Status
pending
