import { useQuery } from '@tanstack/react-query'
import { fetchSecurityReport, type SecurityReport } from '../api/security'

/**
 * S142b — 技能安全報告 hook。
 * data === undefined → loading
 * data === null     → 404（尚未掃描）
 * data === object   → 安全報告完成
 */
export function useSecurityReport(skillId: string | undefined) {
  return useQuery<SecurityReport | null>({
    queryKey: ['skill-security-report', skillId],
    queryFn: () => fetchSecurityReport(skillId!),
    enabled: !!skillId,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  })
}
