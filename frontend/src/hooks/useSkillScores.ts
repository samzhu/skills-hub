import { useQuery } from '@tanstack/react-query'
import { fetchSkillScores, type SkillScores } from '../api/scores'

/**
 * S135b — 品質評分 hook。
 * data === undefined → loading
 * data === null     → 404（尚未評分）
 * data === object   → 評分完成
 */
export function useSkillScores(skillId: string | undefined) {
  return useQuery<SkillScores | null>({
    queryKey: ['skill-scores', skillId],
    queryFn: () => fetchSkillScores(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
