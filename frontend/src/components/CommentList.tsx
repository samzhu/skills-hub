import { Trash2 } from 'lucide-react'
import type { RequestComment } from '@/api/skills'
import { getDisplayName } from '@/lib/displayName'

/**
 * S156c — Comment list；earliest first ASC（對齊 GitHub Issues 風格）。
 *
 * `currentUserId` 用於決定每筆 comment 是否顯示「Delete」按鈕（own only）；
 * 未登入 undefined → 一律不顯。Backend `DELETE /comments/{id}` 仍會 enforce owner-only。
 */
export function CommentList({
  comments,
  currentUserId,
  onDelete,
}: {
  comments: RequestComment[]
  currentUserId: string | undefined
  onDelete: (commentId: string) => void
}) {
  if (comments.length === 0) {
    return (
      <p className="py-6 text-center text-[12px] text-muted-foreground">
        還沒有人留言，分享你的想法吧。
      </p>
    )
  }

  return (
    <ul className="divide-y divide-border">
      {comments.map((c) => (
        <CommentRow
          key={c.id}
          comment={c}
          canDelete={!!currentUserId && currentUserId === c.authorId}
          onDelete={onDelete}
        />
      ))}
    </ul>
  )
}

function CommentRow({
  comment,
  canDelete,
  onDelete,
}: {
  comment: RequestComment
  canDelete: boolean
  onDelete: (commentId: string) => void
}) {
  return (
    <li className="flex items-start gap-3 py-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-[13px] font-medium">
            {getDisplayName({
              author: comment.authorId,
              authorDisplayName: comment.authorDisplayName,
              authorHandle: comment.authorHandle,
            })}
          </span>
          <span className="text-[11px] text-muted-foreground">
            {new Date(comment.createdAt).toLocaleString('zh-TW')}
          </span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed">{comment.content}</p>
      </div>
      {canDelete && (
        <button
          type="button"
          aria-label="刪除留言"
          onClick={() => onDelete(comment.id)}
          className="shrink-0 rounded p-1 text-muted-foreground hover:bg-muted hover:text-red-400"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      )}
    </li>
  )
}
