import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { useFlagsQueue } from '@/hooks/useFlagsQueue'
import { updateFlagStatus, type Flag } from '@/api/flags'
import { FLAG_TYPE_LABEL } from '@/lib/flag-labels'

/**
 * S098e3-T04 — Reviewer queue page `/flags`。
 *
 * List 所有 OPEN flags（跨 skill），每 row 帶 Resolve / Dismiss action button。
 * 點 Resolve → PATCH status=RESOLVED；row 從 OPEN list filter out（refetch
 * 後 query 不再含此 flag）。MVP 任何登入 user 皆可看 (per spec §2.1
 * Feature First)；未來 reviewer role gate 由 @PreAuthorize 補。
 *
 * Trim：
 * - skill name 顯示走 link to `/skills/{skillId}`，user 點進去看 detail；
 *   不為了顯 skill name 而 N+1 fetch（per spec §4.1 / Approach C）
 * - 無 toast feedback；走 mutation 樂觀 UX：列表 refetch 後該 row 自然消失
 */
export function FlagsQueuePage() {
  const { data: flags, isLoading } = useFlagsQueue('OPEN')
  const queryClient = useQueryClient()

  const updateMutation = useMutation({
    mutationFn: ({ skillId, flagId, status }: { skillId: string; flagId: string; status: Flag['status'] }) =>
      updateFlagStatus(skillId, flagId, status),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['flags-queue'] })
    },
  })

  return (
    <AppShell>
      <div className="mb-4">
        <h1 className="text-[22px] font-semibold tracking-tight text-foreground">待審回報</h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          目前 OPEN 狀態的回報；reviewer 可標 Resolve（已處理）或 Dismiss（駁回）。
        </p>
      </div>

      {isLoading && (
        <div className="py-8 text-sm text-muted-foreground">載入中...</div>
      )}

      {!isLoading && (!flags || flags.length === 0) && (
        <EmptyState
          tone="clear"
          headline="目前沒有待審回報"
          sub="新回報送出後會出現在此列表，等待 reviewer 處理。"
        />
      )}

      {flags && flags.length > 0 && (
        <div className="space-y-3">
          {flags.map((flag) => (
            <FlagQueueRow
              key={flag.id}
              flag={flag}
              onResolve={() => updateMutation.mutate({ skillId: flag.skillId, flagId: flag.id, status: 'RESOLVED' })}
              onDismiss={() => updateMutation.mutate({ skillId: flag.skillId, flagId: flag.id, status: 'DISMISSED' })}
              isPending={updateMutation.isPending}
            />
          ))}
        </div>
      )}
    </AppShell>
  )
}

function FlagQueueRow({
  flag,
  onResolve,
  onDismiss,
  isPending,
}: {
  flag: Flag
  onResolve: () => void
  onDismiss: () => void
  isPending: boolean
}) {
  return (
    <div className="rounded-md border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <span className="rounded px-2 py-0.5 text-[11px] font-medium bg-secondary text-foreground/80">
          {FLAG_TYPE_LABEL[flag.type]}
        </span>
        <div className="min-w-0 flex-1">
          {flag.description && (
            <p className="m-0 text-[13px] text-foreground">{flag.description}</p>
          )}
          <div className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
            <Link to={`/skills/${flag.skillId}`} className="text-[#C9C5F2] hover:underline">
              {flag.skillId}
            </Link>
            <span>·</span>
            <span>{flag.reportedBy}</span>
            <span>·</span>
            <span>{new Date(flag.createdAt).toLocaleDateString('zh-TW')}</span>
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={onResolve}
            disabled={isPending}
            className="rounded-md border border-border px-3 py-1.5 text-[12px] font-medium hover:bg-muted disabled:opacity-50"
          >
            Resolve
          </button>
          <button
            type="button"
            onClick={onDismiss}
            disabled={isPending}
            className="rounded-md border border-border px-3 py-1.5 text-[12px] font-medium hover:bg-muted disabled:opacity-50"
          >
            Dismiss
          </button>
        </div>
      </div>
    </div>
  )
}
