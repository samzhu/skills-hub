import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowUp } from 'lucide-react'
import { toggleVote } from '@/api/skills'

/**
 * S096g2-T04 — Vote 按鈕（樂觀更新 toggle）。
 *
 * MVP trim per task spec：backend 列表不回 `votedByMe`，前端僅由本地 mutation
 * result 維護 voted state；page reload 後 voted 視覺重置（vote_count 仍正確）。
 * 待 spec §2.4 follow-up「我 vote 過哪些」view 補進 hydrate path。
 *
 * 樂觀更新：點擊瞬間先翻 voted + ±1 count；server 回應到後 sync 真值
 * （server 為 strong-consistent atomic UPDATE，不會 stale）。
 *
 * `aria-pressed` 對齊 toggle button ARIA pattern — screen reader 正確報「已投/未投」。
 */
export function VoteButton({
  requestId,
  initialCount,
}: {
  requestId: string
  initialCount: number
}) {
  const [voted, setVoted] = useState(false)
  const [count, setCount] = useState(initialCount)
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => toggleVote(requestId),
    onMutate: () => {
      const previousVoted = voted
      const previousCount = count
      setVoted(!previousVoted)
      setCount(previousVoted ? Math.max(0, previousCount - 1) : previousCount + 1)
      return { previousVoted, previousCount }
    },
    onSuccess: (data) => {
      setVoted(data.voted)
      setCount(data.voteCount)
      queryClient.invalidateQueries({ queryKey: ['requests'] })
    },
    onError: (_err, _vars, ctx) => {
      if (ctx) {
        setVoted(ctx.previousVoted)
        setCount(ctx.previousCount)
      }
    },
  })

  return (
    <button
      type="button"
      onClick={() => mutation.mutate()}
      disabled={mutation.isPending}
      aria-pressed={voted}
      aria-label={voted ? '已投票，再點取消' : '投票'}
      className={
        'flex shrink-0 flex-col items-center rounded px-2 py-1 text-[12px] transition disabled:opacity-50 ' +
        (voted ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:bg-muted')
      }
    >
      <ArrowUp className="h-3.5 w-3.5" />
      <span className="font-mono font-medium tabular-nums">{count}</span>
    </button>
  )
}
