import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createCollection } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S096f2-T04 — 建立集合 modal。
 *
 * Mirror CreateRequestModal pattern：fixed overlay + form card + cancel/submit buttons。
 * 4 個欄位：name (≤200) / description (≤2000, optional) / category (≤100) / skillIds list。
 *
 * **Skill picker MVP 走 textarea**（per spec §2.6 trim — fancy multi-select picker
 * defer 至後續 polish）：使用者每行貼一個 skill UUID；submit 前 split + filter empty。
 * Server 端走 SkillNotPublishableException 驗 PUBLISHED + 存在性，回 400 + invalidSkillIds
 * csv message — 失敗訊息直接 echo 給使用者。
 *
 * onSuccess invalidate `['collections']` 觸發 useCollections refetch + onClose。
 * 與 CreateRequestModal 一致：無 toast，靠 modal close + list refresh 視覺回饋。
 */
export function CreateCollectionModal({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState('')
  const [skillIdsText, setSkillIdsText] = useState('')
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => {
      const skillIds = skillIdsText.split(/\s+/).map((s) => s.trim()).filter(Boolean)
      return createCollection({
        name: name.trim(),
        description: description.trim() || null,
        category: category.trim(),
        skillIds,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] })
      onClose()
    },
  })

  const canSubmit =
    name.trim().length > 0 &&
    category.trim().length > 0 &&
    skillIdsText.trim().length > 0 &&
    !mutation.isPending

  return (
    <div
      role="dialog"
      aria-label="建立集合"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-3 text-[16px] font-semibold">建立集合</h3>

        <div className="mb-3">
          <label htmlFor="collection-name" className="mb-1 block text-[12px] text-muted-foreground">
            名稱（最多 200 字）
          </label>
          <input
            id="collection-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={200}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="例：DevOps Starter Pack"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="collection-description" className="mb-1 block text-[12px] text-muted-foreground">
            說明（選填，最多 2000 字）
          </label>
          <textarea
            id="collection-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            maxLength={2000}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="這個集合適合什麼情境？"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="collection-category" className="mb-1 block text-[12px] text-muted-foreground">
            分類（最多 100 字）
          </label>
          <input
            id="collection-category"
            type="text"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            maxLength={100}
            className="w-full rounded-md border border-border bg-background p-2 text-[13px]"
            placeholder="例：DevOps / Frontend / Security"
          />
        </div>

        <div className="mb-3">
          <label htmlFor="collection-skills" className="mb-1 block text-[12px] text-muted-foreground">
            技能 ID 清單（每行一個 skill UUID）
          </label>
          <textarea
            id="collection-skills"
            value={skillIdsText}
            onChange={(e) => setSkillIdsText(e.target.value)}
            rows={4}
            className="w-full rounded-md border border-border bg-background p-2 font-mono text-[12px]"
            placeholder={'sk-1\nsk-2\nsk-3'}
          />
          <p className="mt-1 text-[11px] text-muted-foreground">
            技能 UUID 須為 PUBLISHED 狀態；漂亮的多選選擇器後續版本推出。
          </p>
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
            {mutation.isPending ? '送出中...' : '送出'}
          </button>
        </div>
      </div>
    </div>
  )
}
