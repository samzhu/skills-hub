import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Star, Trash2 } from 'lucide-react'
import { EmptyState } from '@/components/EmptyState'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import { RatingStars } from '@/components/RatingStars'
import { useReviews } from '@/hooks/useReviews'
import { createReview, deleteReview, type Review } from '@/api/reviews'
import { skillKeys } from '@/api/queryKeys'
import type { Skill } from '@/types/skill'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S098e2-T04 — SkillDetail Reviews tab 主體 component。
 *
 * Conditional render：
 * - 0 reviews → EmptyState invite + 「撰寫評論」CTA
 * - N reviews + I haven't reviewed → RatingHero + list + 「撰寫評論」CTA
 * - N reviews + I already reviewed → RatingHero + list（我的 row 加刪除） + 無撰寫 CTA
 *
 * Edit affordance defer per spec §2.4 trim list（MVP create + delete + list 三 op）。
 *
 * 對齊 S112-T03 FlagsList extract pattern（避開 SkillDetailPage Tabs 巢狀的 Radix
 * fireEvent 不可靠；ReviewsPanel 為 single-responsibility component 直接 unit test）。
 */
export function ReviewsPanel({ skill, currentUserId }: { skill: Skill; currentUserId?: string }) {
  const [showForm, setShowForm] = useState(false)
  const { data: reviews, isLoading } = useReviews(skill.id)
  const queryClient = useQueryClient()

  const myReview = reviews?.find((r) => r.authorId === currentUserId)

  const createMutation = useMutation({
    mutationFn: (body: { rating: number; content: string }) => createReview(skill.id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-reviews', skill.id] })
      // skill aggregate 帶 averageRating / reviewCount projection — 重新拉以反映 hero
      queryClient.invalidateQueries({ queryKey: skillKeys.detail(skill.id) })
      setShowForm(false)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (reviewId: string) => deleteReview(skill.id, reviewId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-reviews', skill.id] })
      queryClient.invalidateQueries({ queryKey: skillKeys.detail(skill.id) })
    },
  })

  if (isLoading) {
    return <div className="py-8 text-sm text-muted-foreground">載入中...</div>
  }

  if (!reviews || reviews.length === 0) {
    return (
      <>
        <EmptyState
          tone="invite"
          headline="成為第一個評論這個技能的人"
          sub="你的回饋幫助其他開發者選擇合適的技能。撰寫評論後其他人可以看到你的星等與心得。"
        />
        <div className="mt-4 flex justify-center">
          {/* S139 lazy gate — anonymous → OAuth redirect；authenticated → 開 form */}
          <AuthGatedButton
            type="button"
            onClick={() => setShowForm(true)}
            className="rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-primary/90"
          >
            撰寫評論
          </AuthGatedButton>
        </div>
        {showForm && (
          <ReviewForm
            onClose={() => setShowForm(false)}
            onSubmit={(body) => createMutation.mutate(body)}
            isPending={createMutation.isPending}
            error={createMutation.error}
          />
        )}
      </>
    )
  }

  return (
    <div>
      <RatingHero average={skill.averageRating} count={skill.reviewCount} />

      {!myReview && (
        <div className="mb-4">
          {/* S139 lazy gate — anonymous → OAuth redirect；authenticated → 開 form */}
          <AuthGatedButton
            type="button"
            onClick={() => setShowForm(true)}
            className="rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-primary/90"
          >
            撰寫評論
          </AuthGatedButton>
        </div>
      )}

      <div className="space-y-3">
        {reviews.map((r) => (
          <ReviewRow
            key={r.id}
            review={r}
            isMine={r.authorId === currentUserId}
            onDelete={() => deleteMutation.mutate(r.id)}
          />
        ))}
      </div>

      {showForm && (
        <ReviewForm
          onClose={() => setShowForm(false)}
          onSubmit={(body) => createMutation.mutate(body)}
          isPending={createMutation.isPending}
          error={createMutation.error}
        />
      )}
    </div>
  )
}

function RatingHero({ average, count }: { average: number; count: number }) {
  return (
    <div className="mb-4 flex items-baseline gap-3 rounded-md border border-border bg-card p-4">
      <Star className="h-5 w-5 fill-[#FAC775] text-[#FAC775]" />
      <span className="text-[24px] font-semibold tabular-nums text-foreground">{average.toFixed(1)}</span>
      <span className="text-[14px] text-muted-foreground">{count} 則評論</span>
    </div>
  )
}

function ReviewRow({
  review,
  isMine,
  onDelete,
}: {
  review: Review
  isMine: boolean
  onDelete: () => void
}) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="mb-2 flex items-center gap-2">
        <RatingStars value={review.rating} size={14} />
        <span className="text-[12px] text-muted-foreground">{review.authorId}</span>
        <span className="ml-auto text-[12px] text-muted-foreground">
          {new Date(review.createdAt).toLocaleDateString('zh-TW')}
        </span>
        {isMine && (
          <button
            type="button"
            onClick={onDelete}
            aria-label="刪除我的評論"
            className="ml-2 rounded p-1 text-muted-foreground hover:text-foreground"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      <p className="text-[13px] text-foreground/90">{review.content}</p>
    </div>
  )
}

function ReviewForm({
  onClose,
  onSubmit,
  isPending,
  error,
}: {
  onClose: () => void
  onSubmit: (body: { rating: number; content: string }) => void
  isPending: boolean
  error: Error | null
}) {
  const [rating, setRating] = useState(0)
  const [content, setContent] = useState('')

  const canSubmit = rating >= 1 && rating <= 5 && content.trim().length > 0 && !isPending

  return (
    <div
      role="dialog"
      aria-label="撰寫評論"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">撰寫評論</h3>
        <div className="mb-3">
          <label className="mb-1 block text-[12px] text-muted-foreground">星等</label>
          <RatingStars value={rating} onChange={setRating} size={20} />
        </div>
        <div className="mb-3">
          <label htmlFor="review-content" className="mb-1 block text-[12px] text-muted-foreground">
            內容
          </label>
          <textarea
            id="review-content"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={4}
            maxLength={2000}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="分享你的使用心得（最多 2000 字）"
          />
        </div>
        {error && <p className="mb-2 text-[12px] text-red-500">提交失敗：{localizeApiError(error)}</p>}
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={isPending}
            className="rounded-md border border-border px-3 py-1.5 text-[13px] hover:bg-muted"
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => onSubmit({ rating, content: content.trim() })}
            disabled={!canSubmit}
            className="rounded-md bg-primary px-3 py-1.5 text-[13px] font-medium text-primary-foreground disabled:opacity-50"
          >
            {isPending ? '送出中...' : '送出'}
          </button>
        </div>
      </div>
    </div>
  )
}
