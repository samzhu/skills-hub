import { useState } from 'react'
import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, X } from 'lucide-react'
import { createCollection } from '@/api/skills'
import { localizeApiError } from '@/lib/api-error-messages'
import { useMe } from '@/hooks/useMe'
import { useSkillList } from '@/hooks/useSkillList'
import type { Skill } from '@/types/skill'

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
  const [selectedSkillId, setSelectedSkillId] = useState('')
  const [selectedSkills, setSelectedSkills] = useState<Skill[]>([])
  const queryClient = useQueryClient()
  const { data: me } = useMe()
  const { data: mySkillsPage, isLoading: skillsLoading } = useSkillList({
    author: me?.userId,
    size: 100,
  })

  const publishedSkills = (mySkillsPage?.content ?? []).filter((skill) => skill.status === 'PUBLISHED')
  const selectedIds = new Set(selectedSkills.map((skill) => skill.id))
  const availableSkills = publishedSkills.filter((skill) => !selectedIds.has(skill.id))

  const mutation = useMutation({
    mutationFn: () => {
      return createCollection({
        name: name.trim(),
        description: description.trim() || null,
        category: category.trim(),
        skillIds: selectedSkills.map((skill) => skill.id),
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
    selectedSkills.length > 0 &&
    !mutation.isPending

  const addSelectedSkill = () => {
    const skill = availableSkills.find((item) => item.id === selectedSkillId)
    if (!skill) return
    setSelectedSkills((current) => [...current, skill])
    setSelectedSkillId('')
  }

  const removeSelectedSkill = (skillId: string) => {
    setSelectedSkills((current) => current.filter((skill) => skill.id !== skillId))
  }

  return (
    <div
      role="dialog"
      aria-label="建立集合"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div
        data-testid="create-collection-dialog-surface"
        className="max-h-[calc(100vh-2rem)] w-full max-w-xl overflow-y-auto rounded-md border border-border bg-card p-5"
      >
        <div className="mb-4 flex items-start justify-between gap-4 border-b border-border pb-4">
          <div>
            <h3 className="text-[16px] font-semibold">建立集合</h3>
            <p className="mt-1 text-[12px] leading-5 text-muted-foreground">
              從你已發布的技能挑選幾個，組成可一次安裝的技能包。
            </p>
          </div>
          <button
            type="button"
            aria-label="關閉建立集合"
            onClick={onClose}
            disabled={mutation.isPending}
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

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
          <label htmlFor="collection-skill-picker" className="mb-1 block text-[12px] text-muted-foreground">
            新增技能
          </label>
          {skillsLoading ? (
            <div className="rounded-md border border-border bg-background p-3 text-[12px] text-muted-foreground">
              載入你的技能中...
            </div>
          ) : publishedSkills.length === 0 ? (
            <div className="rounded-md border border-dashed border-border bg-background p-3">
              <p className="text-[13px] font-medium">集合只能加入已發布技能</p>
              <p className="mt-1 text-[12px] text-muted-foreground">
                目前沒有可加入集合的已發布技能；先發布一個技能後再回來建立集合。
              </p>
              <Link to="/publish" className="mt-3 inline-flex rounded-md border border-border px-3 py-1.5 text-[12px] hover:bg-muted">
                前往發布技能
              </Link>
            </div>
          ) : (
            <div className="flex gap-2">
              <select
                id="collection-skill-picker"
                value={selectedSkillId}
                onChange={(e) => setSelectedSkillId(e.target.value)}
                className="min-w-0 flex-1 rounded-md border border-border bg-background p-2 text-[13px]"
              >
                <option value="">選擇已發布技能</option>
                {availableSkills.map((skill) => (
                  <option key={skill.id} value={skill.id}>
                    {formatSkillOption(skill)}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={addSelectedSkill}
                disabled={!selectedSkillId}
                className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-border px-3 py-2 text-[13px] hover:bg-muted disabled:opacity-50"
              >
                <Plus className="h-3.5 w-3.5" />
                新增
              </button>
            </div>
          )}
        </div>

        <div className="mb-3">
          <p className="mb-1 text-[12px] text-muted-foreground">已選技能 {selectedSkills.length}</p>
          <div data-testid="selected-skills-list" className="rounded-md border border-border bg-background">
            {selectedSkills.length === 0 ? (
              <p className="px-3 py-3 text-[12px] text-muted-foreground">尚未加入技能</p>
            ) : (
              selectedSkills.map((skill) => (
                <div key={skill.id} className="flex items-center gap-3 border-b border-border px-3 py-2 last:border-b-0">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-medium">{skill.name}</p>
                    <p className="text-[11px] text-muted-foreground">
                      {skill.category} · v{skill.latestVersion ?? '—'}
                    </p>
                  </div>
                  <button
                    type="button"
                    aria-label={`移除 ${skill.name}`}
                    onClick={() => removeSelectedSkill(skill.id)}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              ))
            )}
          </div>
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
            {mutation.isPending ? '建立中...' : '建立集合'}
          </button>
        </div>
      </div>
    </div>
  )
}

function formatSkillOption(skill: Skill) {
  return `${skill.name} · ${skill.category} · v${skill.latestVersion ?? '—'}`
}
