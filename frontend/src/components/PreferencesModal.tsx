import { useState } from 'react'
import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
} from '@/hooks/useNotificationPreferences'
import type { NotificationPreferences } from '@/api/notifications'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S096h2-T04 — 通知訂閱偏好 modal（3 個 toggle）。
 *
 * Mirror standard modal pattern：fixed overlay + card + cancel/save buttons。
 * 開啟時 lazy fetch 當前 preferences；本地 state 只記 user edits，draft 由 render-time
 * 推導（per React 19 docs「Adjusting some state when a prop changes」— derive in render
 * 比 useEffect+setState sync 不會 cascading render，避免 react-hooks/set-state-in-effect）。
 * Server 回完整 preferences → setQueryData 立即生效（無需 refetch）。
 *
 * S155 #5: 移除「新版本（敬請期待）」項 — placeholder anti-pattern；等真實做時
 * 再加回（可由 S145 訂閱管理 ship 時帶入）。`versions` 欄位仍存於 API；UI 暫不顯示。
 */
export function PreferencesModal({ onClose }: { onClose: () => void }) {
  const { data: current, isLoading } = useNotificationPreferences()
  const mutation = useUpdateNotificationPreferences()
  // Local state 只記 user 編輯過的欄位（diff vs current）；display draft = current ⊕ edits（render-time derive）
  const [edits, setEdits] = useState<Partial<NotificationPreferences>>({})
  const draft: NotificationPreferences | null = current ? { ...current, ...edits } : null

  const toggle = (key: keyof NotificationPreferences) => {
    if (!current || !draft) return
    const newValue = !draft[key]
    setEdits((prev) => {
      // 如果切回 current 的原值，從 edits 移除（保持 diff 最精準）
      if (newValue === current[key]) {
        const { [key]: _removed, ...rest } = prev
        return rest
      }
      return { ...prev, [key]: newValue }
    })
  }

  const handleSave = () => {
    if (!current) return
    // edits 已只含 diff 欄位（toggle 端負責清理同值 entry）
    if (Object.keys(edits).length === 0) {
      onClose()
      return
    }
    mutation.mutate(edits, { onSuccess: () => onClose() })
  }

  return (
    <div
      role="dialog"
      aria-label="通知訂閱偏好"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
    >
      <div className="w-full max-w-md rounded-md border border-border bg-card p-6">
        <h3 className="mb-1 text-[16px] font-semibold">通知訂閱偏好</h3>
        <p className="mb-4 text-[12px] text-muted-foreground">
          選擇要接收哪些類型的通知；關閉後對應事件不再產生新通知，但已收到的紀錄不刪。
        </p>

        {isLoading || !draft ? (
          <div className="py-8 text-center text-[13px] text-muted-foreground">載入設定中...</div>
        ) : (
          <div className="space-y-3">
            <PreferenceRow
              label="回報"
              hint="你發佈的技能被其他人回報時通知你"
              checked={draft.flags}
              onChange={() => toggle('flags')}
            />
            <PreferenceRow
              label="評論"
              hint="你發佈的技能被人寫評論時通知你"
              checked={draft.reviews}
              onChange={() => toggle('reviews')}
            />
            <PreferenceRow
              label="需求"
              hint="你發起的需求被人認領 / 完成時通知你"
              checked={draft.requests}
              onChange={() => toggle('requests')}
            />
          </div>
        )}

        {mutation.isError && (
          <p className="mt-3 text-[12px] text-red-500">儲存失敗：{localizeApiError(mutation.error)}</p>
        )}

        <div className="mt-5 flex justify-end gap-2">
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
            onClick={handleSave}
            disabled={!draft || mutation.isPending}
            className="rounded-md bg-primary px-3 py-1.5 text-[13px] font-medium text-primary-foreground disabled:opacity-50"
          >
            {mutation.isPending ? '儲存中...' : '儲存'}
          </button>
        </div>
      </div>
    </div>
  )
}

function PreferenceRow({
  label,
  hint,
  checked,
  onChange,
  disabled = false,
}: {
  label: string
  hint: string
  checked: boolean
  onChange: () => void
  disabled?: boolean
}) {
  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-md border border-border p-3 hover:bg-muted/30 ${
        disabled ? 'cursor-not-allowed opacity-60' : ''
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        disabled={disabled}
        className="mt-0.5 h-4 w-4"
        aria-label={label}
      />
      <div>
        <div className="text-[13px] font-medium">{label}</div>
        <p className="mt-0.5 text-[12px] text-muted-foreground">{hint}</p>
      </div>
    </label>
  )
}
