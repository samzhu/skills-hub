import { useEffect, useState } from 'react'
import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
} from '@/hooks/useNotificationPreferences'
import type { NotificationPreferences } from '@/api/notifications'
import { localizeApiError } from '@/lib/api-error-messages'

/**
 * S096h2-T04 — 通知訂閱偏好 modal（4 個 toggle）。
 *
 * Mirror CreateRequestModal pattern：fixed overlay + card + cancel/save buttons。
 * 開啟時 lazy fetch 當前 preferences；本地 state 編輯，submit 後 POST partial。
 * Server 回完整 preferences → setQueryData 立即生效（無需 refetch）。
 *
 * Versions toggle 標示「敬請期待」— 後端 listener 不產 versions 通知（spec §2.6 trim）。
 */
export function PreferencesModal({ onClose }: { onClose: () => void }) {
  const { data: current, isLoading } = useNotificationPreferences()
  const mutation = useUpdateNotificationPreferences()
  const [draft, setDraft] = useState<NotificationPreferences | null>(null)

  // 載入後初始化 draft（避免 controlled-input race）
  useEffect(() => {
    if (current && !draft) {
      setDraft(current)
    }
  }, [current, draft])

  const toggle = (key: keyof NotificationPreferences) => {
    if (!draft) return
    setDraft({ ...draft, [key]: !draft[key] })
  }

  const handleSave = () => {
    if (!draft || !current) return
    // 只送有差異的欄位 — backend partial update 標準（null = 不動）
    const diff: Partial<NotificationPreferences> = {}
    ;(Object.keys(draft) as Array<keyof NotificationPreferences>).forEach((k) => {
      if (draft[k] !== current[k]) diff[k] = draft[k]
    })
    mutation.mutate(diff, { onSuccess: () => onClose() })
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
              hint="你發佈的技能被其他人 flag 時通知你"
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
            <PreferenceRow
              label="新版本"
              hint="關注的技能發佈新版本時通知你（敬請期待）"
              checked={draft.versions}
              onChange={() => toggle('versions')}
              disabled
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
