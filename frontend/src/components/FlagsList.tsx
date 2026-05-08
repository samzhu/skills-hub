import { useState } from 'react'
import { EmptyState } from '@/components/EmptyState'
import { AuthGatedButton } from '@/components/AuthGatedButton'
import { FlagSubmitModal } from '@/components/FlagSubmitModal'
import { useFlags } from '@/hooks/useFlags'
import type { Flag } from '@/api/flags'
import { FLAG_TYPE_LABEL, FLAG_STATUS_LABEL, FLAG_STATUS_STYLE } from '@/lib/flag-labels'

/**
 * S112-T03 → S098e3-T03: Skill 回報列表（Flags tab 主體）。
 *
 * 0 flag → 既有 EmptyState；>0 flags → 直接列；後端 ORDER BY desc 故前端不再排。
 * 上方一律顯「回報問題」CTA（S098e3-T03 ship；不論有無既存 flag）；點擊開
 * FlagSubmitModal。
 */
export function FlagsList({ skillId }: { skillId: string }) {
  const { data: flags, isLoading } = useFlags(skillId)
  const [showModal, setShowModal] = useState(false)

  if (isLoading) {
    return <div className="py-8 text-sm text-muted-foreground">載入中...</div>
  }

  return (
    <div>
      <div className="mb-3">
        {/* S139 lazy gate — anonymous → OAuth redirect；authenticated → 開 modal */}
        <AuthGatedButton
          type="button"
          onClick={() => setShowModal(true)}
          className="rounded-md bg-primary px-4 py-2 text-[13px] font-medium text-primary-foreground hover:bg-primary/90"
        >
          回報問題
        </AuthGatedButton>
      </div>

      {!flags || flags.length === 0 ? (
        <EmptyState
          tone="clear"
          headline="目前沒有任何回報"
          sub="若你發現此技能含惡意指令、誤導描述或其他問題，可使用上方按鈕送出回報，由審核者處理。"
        />
      ) : (
        <div className="space-y-2">
          {flags.map((f) => <FlagRow key={f.id} flag={f} />)}
        </div>
      )}

      {showModal && (
        <FlagSubmitModal skillId={skillId} onClose={() => setShowModal(false)} />
      )}
    </div>
  )
}

function FlagRow({ flag }: { flag: Flag }) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex items-center gap-2">
        <span className="rounded px-2 py-0.5 text-[11px] bg-secondary text-foreground/80">
          {FLAG_TYPE_LABEL[flag.type]}
        </span>
        <span
          className="rounded-full px-2 py-0.5 text-[11px] font-medium"
          style={FLAG_STATUS_STYLE[flag.status]}
        >
          {FLAG_STATUS_LABEL[flag.status]}
        </span>
        <span className="ml-auto text-[11px] text-muted-foreground">
          {new Date(flag.createdAt).toLocaleDateString('zh-TW')}
        </span>
      </div>
      {flag.description && (
        <p className="mt-2 text-[13px] text-muted-foreground">{flag.description}</p>
      )}
    </div>
  )
}
