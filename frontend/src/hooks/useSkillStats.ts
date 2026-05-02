import { useQuery } from '@tanstack/react-query'
import { fetchSkillStats } from '../api/skills'

/**
 * S096d3 — fetch per-skill 30d (or 7d/90d) download trend for sparkline.
 * Cache 60s（trends 不會秒級變動）；網路 4xx 不 retry。
 */
export function useSkillStats(id: string | undefined, period: '7d' | '30d' | '90d' = '30d') {
  return useQuery<number[]>({
    queryKey: ['skill-stats', id, period],
    queryFn: () => fetchSkillStats(id!, period),
    enabled: !!id,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
