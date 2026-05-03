import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFlag, type Flag } from '@/api/flags'
import { FLAG_TYPE_LABEL } from '@/lib/flag-labels'

/**
 * S098e3-T03 — Flag 提交 modal。
 *
 * 6 顆 type radio button (per spec §1 visual flow)；description optional
 * (backend 接受 null)。submit 後 invalidate ['skill-flags', skillId] +
 * ['me-flags-summary'] → FlagsList 自動 refetch + MySkillsPage 計數同步。
 *
 * Note: 沒處理 `已收到回報` toast — 走 modal close + list refresh 視覺回饋。
 * Toast 系統若日後加上可在 onSuccess hook 觸發。
 */
const TYPE_OPTIONS: Flag['type'][] = ['malicious', 'spam', 'inappropriate', 'copyright', 'security', 'other']

export function FlagSubmitModal({ skillId, onClose }: { skillId: string; onClose: () => void }) {
  const [type, setType] = useState<Flag['type']>('malicious')
  const [description, setDescription] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () =>
      createFlag(skillId, {
        type,
        description: description.trim() || undefined,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-flags', skillId] })
      queryClient.invalidateQueries({ queryKey: ['me-flags-summary'] })
      onClose()
    },
  })

  return (
    <div
      role="dialog"
      aria-label="回報問題"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">回報問題</h3>

        <fieldset className="mb-3">
          <legend className="mb-2 text-[12px] text-muted-foreground">問題類型</legend>
          <div className="grid grid-cols-2 gap-2">
            {TYPE_OPTIONS.map((t) => (
              <label
                key={t}
                className="inline-flex cursor-pointer items-center gap-2 rounded-md border border-border bg-background px-3 py-1.5 text-[13px] hover:bg-muted"
              >
                <input
                  type="radio"
                  name="flag-type"
                  value={t}
                  checked={type === t}
                  onChange={() => setType(t)}
                />
                {FLAG_TYPE_LABEL[t]}
              </label>
            ))}
          </div>
        </fieldset>

        <div className="mb-3">
          <label htmlFor="flag-description" className="mb-1 block text-[12px] text-muted-foreground">
            說明（可選，最多 500 字）
          </label>
          <textarea
            id="flag-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            maxLength={500}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="補充細節幫助 reviewer 判斷"
          />
        </div>

        {mutation.isError && (
          <p className="mb-2 text-[12px] text-red-500">
            提交失敗：{mutation.error.message}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={mutation.isPending}
            className="rounded-md border border-border px-3 py-1.5 text-[13px] hover:bg-muted"
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            className="rounded-md bg-primary px-3 py-1.5 text-[13px] font-medium text-primary-foreground disabled:opacity-50"
          >
            {mutation.isPending ? '送出中...' : '送出'}
          </button>
        </div>
      </div>
    </div>
  )
}
