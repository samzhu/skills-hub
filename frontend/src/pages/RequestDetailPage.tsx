import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { AppShell } from '@/components/AppShell'
import { EmptyState } from '@/components/EmptyState'
import { VoteButton } from '@/components/VoteButton'
import { CommentList } from '@/components/CommentList'
import { CommentForm } from '@/components/CommentForm'
import {
  useRequestDetail,
  usePostComment,
  useDeleteComment,
  useDeleteRequest,
} from '@/hooks/useRequestDetail'
import { useMe } from '@/hooks/useMe'
import { localizeApiError } from '@/lib/api-error-messages'
import { getDisplayName } from '@/lib/displayName'

/**
 * S156c — /requests/:id detail page。
 *
 * Layout：PageHeader (title + requester + 日期 + vote)；Description；Delete button
 * (canDelete only)；CommentList (ASC) + CommentForm。Backend GET /{id} 回的
 * `canDelete` 直接決定刪除按鈕可見性 — frontend 不重複 ownership 計算（per spec §2.2
 * viewerActions 對齊 backend-computed pattern）。
 */
export function RequestDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { data: request, isLoading, isError } = useRequestDetail(id)
  const { data: me } = useMe()

  const postCommentMutation = usePostComment(id ?? '')
  const deleteCommentMutation = useDeleteComment(id ?? '')
  const deleteRequestMutation = useDeleteRequest()

  if (isLoading) {
    return (
      <AppShell>
        <div className="flex items-center justify-center py-16 text-muted-foreground">載入中...</div>
      </AppShell>
    )
  }

  if (isError || !request) {
    return (
      <AppShell>
        <EmptyState
          tone="redirect"
          headline="找不到此需求。"
          sub="此需求不存在或已被刪除。"
          primaryAction={{ label: '回需求看板', href: '/requests' }}
        />
      </AppShell>
    )
  }

  const onPostComment = (content: string) => {
    postCommentMutation.mutate(content, {
      onError: (err) => toast.error(`留言失敗：${localizeApiError(err)}`),
    })
  }

  const onDeleteComment = (commentId: string) => {
    if (!window.confirm('確定刪除這則留言？')) return
    deleteCommentMutation.mutate(commentId, {
      onError: (err) => toast.error(`刪除失敗：${localizeApiError(err)}`),
    })
  }

  const onDeleteRequest = () => {
    if (!window.confirm(`確定刪除需求「${request.title}」？此動作無法復原。`)) return
    deleteRequestMutation.mutate(request.id, {
      onSuccess: () => {
        toast.success('需求已刪除')
        navigate('/requests')
      },
      onError: (err) => toast.error(`刪除失敗：${localizeApiError(err)}`),
    })
  }

  const createdDate = new Date(request.createdAt).toLocaleDateString('zh-TW')
  const requesterDisplayName = getDisplayName({
    author: request.requesterId,
    authorDisplayName: request.requesterDisplayName,
    authorHandle: request.requesterHandle,
  })
  const requesterMeta = requesterDisplayName ? `${requesterDisplayName} · ${createdDate}` : createdDate

  return (
    <AppShell>
      {/* Back link */}
      <Link
        to="/requests"
        className="mb-4 inline-flex items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="h-3 w-3" />
        需求看板
      </Link>

      {/* Header */}
      <div className="mb-6 flex items-start gap-4">
        <VoteButton requestId={request.id} initialCount={request.voteCount} />
        <div className="min-w-0 flex-1">
          <h1 className="text-[22px] font-semibold tracking-tight">{request.title}</h1>
          <p className="mt-1 text-[12px] text-muted-foreground">
            {requesterMeta}
          </p>
        </div>
        {request.canDelete && (
          <button
            type="button"
            data-testid="delete-request-btn"
            onClick={onDeleteRequest}
            disabled={deleteRequestMutation.isPending}
            className="inline-flex shrink-0 items-center gap-1 rounded-md border border-red-700/40 px-3 py-1.5 text-[13px] text-red-400 hover:bg-red-950/30 disabled:opacity-50"
          >
            <Trash2 className="h-3.5 w-3.5" />
            {deleteRequestMutation.isPending ? '刪除中...' : '刪除'}
          </button>
        )}
      </div>

      {/* Description */}
      <section className="mb-8 rounded-lg border border-border bg-card p-4">
        <p className="whitespace-pre-wrap text-[13px] leading-relaxed">{request.description}</p>
      </section>

      {/* Comments */}
      <section>
        <h2 className="mb-3 text-[14px] font-semibold">留言（{request.comments.length}）</h2>
        <CommentList
          comments={request.comments}
          currentUserId={me?.userId}
          onDelete={onDeleteComment}
        />
        <CommentForm onSubmit={onPostComment} isPending={postCommentMutation.isPending} />
      </section>
    </AppShell>
  )
}
