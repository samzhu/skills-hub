import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createRequest } from '@/api/skills'

/**
 * S096g2-T04 — 發起新需求 modal。
 *
 * Mirror FlagSubmitModal pattern：title input + description textarea + submit。
 * 後端 cap title ≤ 200 / description ≤ 2000；前端對齊 maxLength。
 *
 * onSuccess 後 invalidate `['requests']` 觸發 useRequests refetch（list 自動更新）+ onClose。
 * 與 FlagSubmitModal 一致：無 toast，靠 modal close + list refresh 視覺回饋。
 */
export function CreateRequestModal({ onClose }: { onClose: () => void }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => createRequest({ title: title.trim(), description: description.trim() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['requests'] })
      onClose()
    },
  })

  const canSubmit = title.trim().length > 0 && description.trim().length > 0 && !mutation.isPending

  return (
    <div
      role="dialog"
      aria-label="發起新需求"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">發起新需求</h3>

        <div className="mb-3">
          <label htmlFor="request-title" className="mb-1 block text-[12px] text-muted-foreground">
            標題（最多 200 字）
          </label>
          <input
            id="request-title"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={200}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="我希望某種 skill 存在..."
          />
        </div>

        <div className="mb-3">
          <label htmlFor="request-description" className="mb-1 block text-[12px] text-muted-foreground">
            說明（最多 2000 字）
          </label>
          <textarea
            id="request-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={5}
            maxLength={2000}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="說明使用情境、想要的功能、預期效果..."
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
            disabled={!canSubmit}
            className="rounded-md bg-primary px-3 py-1.5 text-[13px] font-medium text-primary-foreground disabled:opacity-50"
          >
            {mutation.isPending ? '送出中...' : '送出'}
          </button>
        </div>
      </div>
    </div>
  )
}
