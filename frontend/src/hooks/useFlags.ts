import { useQuery } from '@tanstack/react-query'
import { fetchFlags, type Flag } from '../api/flags'

/**
 * S112 — fetch single skill 的 flags（社群回報）。
 * Cache 60s（flags 不會秒級變動）；網路 4xx 不 retry（per `useSkillStats` pattern）。
 */
export function useFlags(skillId: string | undefined) {
  return useQuery<Flag[]>({
    queryKey: ['skill-flags', skillId],
    queryFn: () => fetchFlags(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
