import { useQuery } from '@tanstack/react-query'
import { fetchReviews, type Review } from '../api/reviews'

/**
 * S098e2 — fetch single skill 的 reviews（後端 ORDER BY createdAt DESC）。
 * Cache 60s（per `useFlags` / `useSkillStats` pattern）；網路 4xx 不 retry。
 */
export function useReviews(skillId: string | undefined) {
  return useQuery<Review[]>({
    queryKey: ['skill-reviews', skillId],
    queryFn: () => fetchReviews(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
