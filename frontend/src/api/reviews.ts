import { apiFetch } from './client'

/**
 * S098e2 — Skill Review API client。
 *
 * `rating` 範圍 1-5（後端 CHECK 終局守門 + Aggregate factory validate）。
 * `(skillId, authorId)` 為 UNIQUE，每 user 每 skill 1 則 review。
 */
export interface Review {
  id: string
  skillId: string
  authorId: string
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
  return apiFetch<void>(`/skills/${skillId}/reviews/${reviewId}`, { method: 'DELETE' })
}
