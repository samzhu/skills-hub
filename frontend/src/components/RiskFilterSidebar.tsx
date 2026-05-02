import { cn } from '@/lib/utils'
import type { RiskLevel, Skill } from '@/types/skill'

/**
 * S098d2 — Homepage risk filter sidebar (client-side aggregation)。
 *
 * 4 tier checkbox toggles + count breakdown 來自當前 `skillsPage.content`。
 * 不打 backend 額外 endpoint — pageSize=20 下計數是「當前頁面看到的數量」，
 * 跨頁不累計（trade-off：避免新 endpoint；user 翻頁會看到不同 count）。
 *
 * 視覺：CategorySidebar 上方獨立 group；同樣 selected/total 樣式。
 */

interface RiskFilterSidebarProps {
  /** 當前頁的所有 skill — 用於計算每 tier 的 count */
  skills: Skill[]
  /** 目前選中的 risk levels；空 Set = 「不篩選 = 全顯」(初始即如此) */
  selected: Set<RiskLevel>
  onToggle: (level: RiskLevel) => void
  /** 「全部」reset — 清空選擇 */
  onClear: () => void
}

const TIER_ORDER: RiskLevel[] = ['NONE', 'LOW', 'MEDIUM', 'HIGH']
const TIER_LABEL: Record<RiskLevel, string> = {
  NONE: '無風險',
  LOW: '低風險',
  MEDIUM: '中風險',
  HIGH: '高風險',
}
// dot color per tier (對齊 RiskBadge 的顏色：success / info / warning / danger)
const TIER_DOT: Record<RiskLevel, string> = {
  NONE: 'bg-[#A8A49C]',
  LOW: 'bg-[#6FD8B0]',
  MEDIUM: 'bg-[#FAC775]',
  HIGH: 'bg-[#F2A6A6]',
}

export function RiskFilterSidebar({ skills, selected, onToggle, onClear }: RiskFilterSidebarProps) {
  // S098d2: client-side count — Skill.riskLevel 為可能為 null（尚未掃描完）
  const counts: Record<RiskLevel, number> = { NONE: 0, LOW: 0, MEDIUM: 0, HIGH: 0 }
  for (const s of skills) {
    if (s.riskLevel) counts[s.riskLevel]++
  }
  const total = skills.length
  const hasFilter = selected.size > 0

  return (
    <div className="space-y-1">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        風險等級
      </h3>
      <button
        onClick={onClear}
        className={cn(
          'flex w-full items-center justify-between rounded-md px-3 py-1.5 text-sm',
          !hasFilter ? 'bg-accent font-medium' : 'hover:bg-accent/50',
        )}
      >
        <span>全部</span>
        <span className="text-xs text-muted-foreground">{total}</span>
      </button>
      {TIER_ORDER.map((tier) => {
        const isOn = selected.has(tier)
        return (
          <button
            key={tier}
            onClick={() => onToggle(tier)}
            className={cn(
              'flex w-full items-center justify-between rounded-md px-3 py-1.5 text-sm',
              isOn ? 'bg-accent font-medium' : 'hover:bg-accent/50',
            )}
          >
            <span className="flex items-center gap-2">
              <span className={cn('inline-block h-1.5 w-1.5 rounded-full', TIER_DOT[tier])} />
              {TIER_LABEL[tier]}
            </span>
            <span className="text-xs text-muted-foreground">{counts[tier]}</span>
          </button>
        )
      })}
    </div>
  )
}
