import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { updateCollection, type CollectionDetail } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S164b — 編輯集合 metadata + skillIds modal。
 *
 * Mirror CreateCollectionModal pattern + EditSkillModal「prefill 當前值 + unchanged
 * disable submit」設計（S163b 既驗）。skillIds 在 textarea 一行一個 UUID（與
 * CreateCollectionModal 一致；S150 frontend skill picker MVP 留 textarea）。
 *
 * onSuccess invalidate ['collection', id] + ['collections']，CollectionDetailPage
 * 與列表自動 refresh + onClose。
 */
export function EditCollectionModal({
  collection,
  onClose,
}: {
  collection: CollectionDetail
  onClose: () => void
}) {
  const [name, setName] = useState(collection.name)
  const [description, setDescription] = useState(collection.description ?? '')
  const [category, setCategory] = useState(collection.category)
  const initialSkillIds = collection.skills.map((s) => s.id).join('\n')
  const [skillIdsText, setSkillIdsText] = useState(initialSkillIds)
  const queryClient = useQueryClient()

  const trimmedName = name.trim()
  const trimmedDesc = description.trim()
  const trimmedCat = category.trim()
  const skillIdsList = skillIdsText.split(/\s+/).map((s) => s.trim()).filter(Boolean)

  const mutation = useMutation({
    mutationFn: () =>
      updateCollection(collection.id, {
        name: trimmedName,
        description: trimmedDesc || null,
        category: trimmedCat,
        skillIds: skillIdsList,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collection', collection.id] })
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      toast.success('集合已更新')
      onClose()
    },
    onError: (err) => {
      toast.error(`更新失敗：${localizeApiError(err)}`)
    },
  })

  const unchanged =
    trimmedName === collection.name &&
    trimmedDesc === (collection.description ?? '').trim() &&
    trimmedCat === collection.category &&
    skillIdsList.join(',') === collection.skills.map((s) => s.id).join(',')
  const canSubmit =
    trimmedName.length > 0 &&
    trimmedCat.length > 0 &&
    skillIdsList.length > 0 &&
    !unchanged &&
    !mutation.isPending

  return (
    <div
      role="dialog"
      aria-label="編輯集合"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">編輯集合</h3>

        <div className="mb-3">
          <label htmlFor="edit-collection-name" className="mb-1 block text-[12px] text-muted-foreground">
            名稱（最多 200 字）
          </label>
          <input
            id="edit-collection-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={200}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="edit-collection-description" className="mb-1 block text-[12px] text-muted-foreground">
            說明（選填，最多 2000 字）
          </label>
          <textarea
            id="edit-collection-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            maxLength={2000}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="edit-collection-category" className="mb-1 block text-[12px] text-muted-foreground">
            分類（最多 100 字）
          </label>
          <input
            id="edit-collection-category"
            type="text"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            maxLength={100}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="edit-collection-skills" className="mb-1 block text-[12px] text-muted-foreground">
            技能 ID 清單（每行一個 skill UUID；整段覆蓋）
          </label>
          <textarea
            id="edit-collection-skills"
            value={skillIdsText}
            onChange={(e) => setSkillIdsText(e.target.value)}
            rows={4}
            className="w-full rounded-md border border-border bg-background p-2 font-mono text-[12px]"
          />
        </div>

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
