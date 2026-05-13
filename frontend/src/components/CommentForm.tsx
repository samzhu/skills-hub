import { useState } from 'react'

/**
 * S156c — Comment 輸入表單；空字串 disable 送出 + 送出後清空 textarea。
 *
 * Caller 端決定 mutation 行為（useFormComment hook）— 本 component 純 controlled
 * textarea，不直接 mutate。`isPending` 由 caller 從 mutation 狀態傳入，按下後 disable
 * 避免重複送出。
 */
export function CommentForm({
  onSubmit,
  isPending,
}: {
  onSubmit: (content: string) => void
  isPending: boolean
}) {
  const [content, setContent] = useState('')

  const trimmed = content.trim()
  const canSubmit = trimmed.length > 0 && !isPending

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    onSubmit(trimmed)
    setContent('')
  }

  return (
    <form onSubmit={handleSubmit} className="mt-4 space-y-2">
      <label htmlFor="comment-textarea" className="sr-only">
        留言
      </label>
      <textarea
        id="comment-textarea"
        value={content}
        onChange={(e) => setContent(e.target.value)}
        rows={3}
        maxLength={5000}
        placeholder="留下你的想法..."
        disabled={isPending}
        className="w-full rounded-md border border-border bg-background px-3 py-2 text-[13px] disabled:opacity-50"
      />
      <div className="flex justify-end">
        <button
          type="submit"
          disabled={!canSubmit}
          className="rounded-md bg-primary px-4 py-1.5 text-[13px] font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          {isPending ? '送出中...' : '送出'}
        </button>
      </div>
    </form>
  )
}
