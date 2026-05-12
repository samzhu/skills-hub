import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { updateSkill } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'
import type { Skill } from '@/types/skill'

/**
 * S163b — 編輯技能 metadata modal。
 *
 * Mirror CreateCollectionModal pattern（fixed overlay + form card + cancel/submit）。
 * 只開放 description / category 兩欄；name / version 在 backend DTO surface 已過濾，
 * UI 端不顯（避免使用者誤以為可改）。
 *
 * 預填 skill 當前值 — 對應 S163 AC-7「預填當前 description / category」。
 *
 * onSuccess invalidate skill detail query + onClose；無 toast，靠 modal close + page
 * refetch 自然回饋。
 */
export function EditSkillModal({
  skill,
  onClose,
}: {
  skill: Skill
  onClose: () => void
}) {
  const [description, setDescription] = useState(skill.description ?? '')
  const [category, setCategory] = useState(skill.category ?? '')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () =>
      updateSkill(skill.id, {
        description: description.trim() || null,
        category: category.trim() || null,
      }),
    onSuccess: () => {
      // SkillDetailPage 用兩種 key 取 skill：by id (`/skills/:id`) + by author/name canonical
      queryClient.invalidateQueries({ queryKey: ['skill', skill.id] })
      queryClient.invalidateQueries({ queryKey: ['skills'] })
      toast.success('技能已更新')
      onClose()
    },
    onError: (err) => {
      // inline error 仍保留（modal 內看得到立即回饋）；toast 補一份避免關掉 modal 後沒提示
      toast.error(`更新失敗：${localizeApiError(err)}`)
    },
  })

  const trimmedDesc = description.trim()
  const trimmedCat = category.trim()
  const unchanged =
    trimmedDesc === (skill.description ?? '').trim() &&
    trimmedCat === (skill.category ?? '').trim()
  const canSubmit =
    trimmedDesc.length > 0 && trimmedCat.length > 0 && !unchanged && !mutation.isPending

  return (
    <div
      role="dialog"
      aria-label="編輯技能"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">編輯技能</h3>

        <div className="mb-3">
          <label htmlFor="edit-skill-description" className="mb-1 block text-[12px] text-muted-foreground">
            描述（最多 1024 字）
          </label>
          <textarea
            id="edit-skill-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={4}
            maxLength={1024}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="edit-skill-category" className="mb-1 block text-[12px] text-muted-foreground">
            分類
          </label>
          <input
            id="edit-skill-category"
            type="text"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            maxLength={100}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="例：DevOps / Frontend / Security"
          />
        </div>

        <p className="mb-3 text-[11px] text-muted-foreground">
          技能名稱與版本號不可在此編輯（透過上傳新版本變更）。
        </p>

        {mutation.isError && (
          <p className="mb-2 text-[12px] text-red-500">
            提交失敗：{localizeApiError(mutation.error)}
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
            {mutation.isPending ? '送出中...' : '儲存'}
          </button>
        </div>
      </div>
    </div>
  )
}
