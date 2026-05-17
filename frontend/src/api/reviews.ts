import { apiFetch, apiFetchVoid } from './client'

/**
 * S098e2 — Skill Review API client。
 *
 * `rating` 範圍 1-5（後端 CHECK 終局守門 + Aggregate factory validate）。
 * `(skillId, authorId)` 為 UNIQUE，每 user 每 skill 1 則 review。
 */
export interface Review {
  id: string
  skillId: string
  /** S192: platform user_id；delete ownership comparison 用，不是人類 label。 */
  authorId: string
  /** S192: review row 的人類可讀 reviewer label。 */
  authorDisplayName?: string | null
  /** S192: reviewer handle，可作人類可讀 fallback。 */
  authorHandle?: string | null
  rating: number
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
  return apiFetchVoid(`/skills/${skillId}/reviews/${reviewId}`, { method: 'DELETE' })
}
