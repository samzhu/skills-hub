import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchRequest,
  postComment,
  deleteComment,
  deleteRequest,
  type RequestDetail,
} from '../api/skills'

/**
 * S156c — Request detail（GET /requests/{id}）含 comments + canDelete。
 *
 * Cache 30s（vote count 高頻變動；comment 偶發；reviewer 期望 fresh）；
 * `enabled: !!id` 對齊 useCollection / useSkill 既驗 pattern。
 */
export function useRequestDetail(id: string | undefined) {
  return useQuery<RequestDetail>({
    queryKey: ['request', id],
    queryFn: () => fetchRequest(id!),
    enabled: !!id,
    staleTime: 30 * 1000,
  })
}

/** S156c AC-5 — POST comment；onSuccess invalidate request detail + list cache。 */
export function usePostComment(requestId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content: string) => postComment(requestId, { content }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['request', requestId] })
      queryClient.invalidateQueries({ queryKey: ['requests'] })
    },
  })
}

/** S156c AC-6 — DELETE comment (owner-only by backend guard)；onSuccess invalidate detail。 */
export function useDeleteComment(requestId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (commentId: string) => deleteComment(requestId, commentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['request', requestId] })
    },
  })
}

/** S156c AC-7 — DELETE request (requester-only)；onSuccess caller 自決 navigate。 */
export function useDeleteRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (requestId: string) => deleteRequest(requestId),
    onSuccess: (_data, requestId) => {
      queryClient.invalidateQueries({ queryKey: ['request', requestId] })
      queryClient.invalidateQueries({ queryKey: ['requests'] })
    },
  })
}
