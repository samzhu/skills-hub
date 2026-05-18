import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createRequest } from '@/api/skills'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S196-T02 — Inline request creation panel for `/requests`.
 */
export function RequestCreatePanel({ onCreated }: { onCreated: () => void }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => createRequest({ title: title.trim(), description: description.trim() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['requests'] })
      setTitle('')
      setDescription('')
      onCreated()
    },
  })

  const canSubmit = title.trim().length > 0 && description.trim().length > 0 && !mutation.isPending

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
      <section className="rounded-lg border border-border bg-card p-6">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-muted-foreground">我要開需求</p>
        <h2 className="mt-1 text-[18px] font-semibold tracking-tight">把工作問題開成一筆 skill 需求</h2>
        <p className="mt-2 max-w-2xl text-[13px] leading-relaxed text-muted-foreground">
          寫下你遇到的工作問題；送出後會出現在瀏覽需求，其他人可以按「我也要」把它往前推。
        </p>

        <div className="mt-5 grid gap-4">
          <label className="grid gap-1 text-[12px] text-muted-foreground" htmlFor="request-title">
            需求標題（最多 200 字）
            <input
              id="request-title"
              type="text"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              maxLength={200}
              className="rounded-md border border-border bg-background p-2 text-[13px] text-foreground"
              placeholder="docker compose linter"
            />
          </label>

          <label className="grid gap-1 text-[12px] text-muted-foreground" htmlFor="request-description">
            需求說明（最多 2000 字）
            <textarea
              id="request-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={6}
              maxLength={2000}
              className="rounded-md border border-border bg-background p-2 text-[13px] text-foreground"
              placeholder="說明使用情境、想要的功能、預期效果..."
            />
          </label>

          {mutation.isError && (
            <p className="text-[12px] text-red-500">
              提交失敗：{localizeApiError(mutation.error)}
            </p>
          )}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-[12px] text-muted-foreground">不需要指定負責人；票數會幫需求排序。</p>
            <AuthGatedButton
              type="button"
              onClick={() => mutation.mutate()}
              disabled={!canSubmit}
              className="rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground disabled:opacity-50"
            >
              {mutation.isPending ? '送出中...' : '送出需求'}
            </AuthGatedButton>
          </div>
        </div>
      </section>

      <aside className="space-y-3">
        <section className="rounded-lg border border-border bg-card p-4">
          <h2 className="text-[14px] font-semibold">送出後會發生什麼</h2>
          <div className="mt-3 space-y-2 text-[12px] text-muted-foreground">
            <p><strong className="text-foreground">1. 出現在瀏覽需求</strong> 其他人可以看見、留言、按「我也要」。</p>
            <p><strong className="text-foreground">2. 票數推高排序</strong> 票數越高，越容易被作者看見。</p>
            <p><strong className="text-foreground">3. 作者自願回覆</strong> 有人想做時，可以從 detail 頁留言協作。</p>
          </div>
        </section>
      </aside>
    </div>
  )
}
