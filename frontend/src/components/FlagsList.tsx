import { EmptyState } from '@/components/EmptyState'
import { useFlags } from '@/hooks/useFlags'
import type { Flag } from '@/api/flags'
import { FLAG_TYPE_LABEL, FLAG_STATUS_LABEL, FLAG_STATUS_STYLE } from '@/lib/flag-labels'

/**
 * S112-T03: Skill 回報列表（Flags tab 主體）。
 *
 * 0 flag → 既有 EmptyState；>0 flags → 直接列；後端 ORDER BY desc 故前端不再排。
 * `status` pill 預留 RESOLVED 樣式但目前 backend 只寫 OPEN（per S072 / S058
 * createFlag 寫死 "OPEN"），UI 結構待 S098e3 reviewer 流程上線自然支援。
 */
export function FlagsList({ skillId }: { skillId: string }) {
  const { data: flags, isLoading } = useFlags(skillId)
  if (isLoading) {
    return <div className="py-8 text-sm text-muted-foreground">載入中...</div>
  }
  if (!flags || flags.length === 0) {
    return (
      <EmptyState
        tone="clear"
        headline="目前沒有任何回報"
        sub="若你發現此技能含惡意指令、誤導 description 或其他問題，回報功能即將推出，可送至審核佇列由 reviewer 處理。"
      />
    )
  }
  return (
    <div className="space-y-2">
      {flags.map((f) => <FlagRow key={f.id} flag={f} />)}
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
