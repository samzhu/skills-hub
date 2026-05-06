import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createGrant, fetchGrants, revokeGrant, type CreateGrantRequest } from '../api/grants'

/** S114a — fetch grants list for a skill; only fires when modal is open (enabled gate). */
export function useGrants(skillId: string | undefined) {
  return useQuery({
    queryKey: ['grants', skillId],
    queryFn: () => fetchGrants(skillId!),
    enabled: !!skillId,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: false,
  })
}

/** S114a — create a new VIEWER grant; invalidates grants list on success. */
export function useCreateGrant(skillId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateGrantRequest) => createGrant(skillId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['grants', skillId] }),
  })
}

/** S114a — revoke an existing grant; invalidates grants list on success. */
export function useRevokeGrant(skillId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (grantId: string) => revokeGrant(skillId, grantId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['grants', skillId] }),
  })
}
