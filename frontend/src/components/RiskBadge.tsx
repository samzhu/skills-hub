import { Badge } from '@/components/ui/badge'

/**
 * 各風險等級對應的 Tailwind 樣式。
 * hover 顏色與 default 相同（`hover:bg-*-100`），用以覆蓋 Badge 元件內建的 hover 效果，
 * 確保風險徽章的顏色在滑鼠懸停時保持不變。
 *
 * 若未來新增新等級（如 CRITICAL），未匹配的 key 會 fallback 至空字串（無額外樣式）。
 */
const riskStyles: Record<string, string> = {
  LOW: 'bg-green-100 text-green-800 hover:bg-green-100',
  MEDIUM: 'bg-amber-100 text-amber-800 hover:bg-amber-100',
  HIGH: 'bg-red-100 text-red-800 hover:bg-red-100',
}

/**
 * 風險評估等級徽章元件。
 *
 * 根據後端 RiskScanner 評估結果顯示對應顏色的徽章：
 * - LOW → 綠色（低風險）
 * - MEDIUM → 琥珀色（中風險）
 * - HIGH → 紅色（高風險）
 * - null → 灰色（未評估）
 *
 * @param level 風險等級字串，或 null（尚未評估）
 */
export function RiskBadge({ level }: { level: string | null }) {
  if (!level) return <Badge variant="secondary">未評估</Badge>
  return (
    <Badge className={riskStyles[level] ?? ''} variant="secondary">
      {level === 'LOW' ? '低風險' : level === 'MEDIUM' ? '中風險' : '高風險'}
    </Badge>
  )
}
