import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { claimRequest, releaseClaim, fulfillRequest, deleteRequest, type SkillRequest } from '@/api/skills'

/**
 * S096g2-T04 — state-aware action buttons per request row。
 *
 * 4 種狀態決定可見 buttons：
 *   - OPEN   + 我是 requester     → 「刪除」（confirm）
 *   - OPEN   + 我非 requester     → 「認領」
 *   - IN_PROGRESS + 我是 claimer  → 「釋放」+「上架完成」(fulfill 走 prompt() 取 skillId — fancy picker per spec §2.4 defer)
 *   - FULFILLED + fulfilledSkillId → 「查看技能」link
 *   - 其他組合 → 無 button（僅 status pill 已在 row 顯）
 *
 * 統一 invalidate `['requests']` 觸發 list refetch；mutation pending 時 disabled。
 *
 * 注意：fulfill 走 `window.prompt('輸入 PUBLISHED skill UUID')` 是 MVP trim
 * （per task spec §Implementation outline + spec §2.4）；正式版 future spec
 * 接入 useSkillList({author: me.sub, status: 'PUBLISHED'}) 改 modal picker。
 */
export function RequestActionBar({
  request,
  currentUserId,
}: {
  request: SkillRequest
  currentUserId: string | undefined
}) {
  const queryClient = useQueryClient()
  const isRequester = currentUserId === request.requesterId
  const isClaimer = currentUserId === request.claimerId

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['requests'] })

  const claimMutation = useMutation({
    mutationFn: () => claimRequest(request.id),
    onSuccess: invalidate,
  })
  const releaseMutation = useMutation({
    mutationFn: () => releaseClaim(request.id),
    onSuccess: invalidate,
  })
  const fulfillMutation = useMutation({
    mutationFn: (skillId: string) => fulfillRequest(request.id, skillId),
    onSuccess: invalidate,
  })
  const deleteMutation = useMutation({
    mutationFn: () => deleteRequest(request.id),
    onSuccess: invalidate,
  })

  const anyPending =
    claimMutation.isPending ||
    releaseMutation.isPending ||
    fulfillMutation.isPending ||
    deleteMutation.isPending

  if (request.status === 'FULFILLED' && request.fulfilledSkillId) {
    return (
      <Link
        to={`/skills/${request.fulfilledSkillId}`}
        className="rounded-md border border-border px-3 py-1 text-[12px] hover:bg-muted"
      >
        查看技能
      </Link>
    )
  }

  if (request.status === 'OPEN') {
    if (isRequester) {
      return (
        <button
          type="button"
          disabled={anyPending}
          onClick={() => {
            if (window.confirm(`確定刪除需求「${request.title}」？`)) {
              deleteMutation.mutate()
            }
          }}
          className="rounded-md border border-border px-3 py-1 text-[12px] text-red-400 hover:bg-red-500/10 disabled:opacity-50"
        >
          刪除
        </button>
      )
    }
    return (
      <button
        type="button"
        disabled={anyPending}
        onClick={() => claimMutation.mutate()}
        className="rounded-md bg-primary px-3 py-1 text-[12px] font-medium text-primary-foreground disabled:opacity-50"
      >
        認領
      </button>
    )
  }

  if (request.status === 'IN_PROGRESS' && isClaimer) {
    return (
      <div className="flex gap-1.5">
        <button
          type="button"
          disabled={anyPending}
          onClick={() => releaseMutation.mutate()}
          className="rounded-md border border-border px-3 py-1 text-[12px] hover:bg-muted disabled:opacity-50"
        >
          釋放
        </button>
        <button
          type="button"
          disabled={anyPending}
          onClick={() => {
            const skillId = window.prompt('輸入已 PUBLISHED 的 skill UUID（暫無 picker UI）：')
            if (skillId && skillId.trim()) {
              fulfillMutation.mutate(skillId.trim())
            }
          }}
          className="rounded-md bg-primary px-3 py-1 text-[12px] font-medium text-primary-foreground disabled:opacity-50"
        >
          上架完成
        </button>
      </div>
    )
  }

  return null
}
