import { Badge } from '@/components/ui/badge'

/**
 * S096c — 4-tier RiskLevel badge with dark theme tokens (per ADR-future + PRD D27).
 *
 * 對齊 Cisco Skill Scanner + CVSS None band：
 * - NONE 綠 (success-soft) — 純文件 skill，scanner 0 findings + 無 capability declaration
 *   注意：NONE ≠ certified safe，僅表示未抓到 known patterns（tooltip 補語意 caveat）
 * - LOW  藍 (info-soft) — 0 findings 但聲明 capability，OR findings 全 LOW
 * - MEDIUM 琥珀 (warning-soft) — 含腳本但無危險模式，需人工複核
 * - HIGH 紅 (danger-soft) — 危險指令或敏感路徑，應優先審查
 *
 * 色彩用 inline-style hex 對齊 DESIGN.md v2 dark theme semantic palette
 * （rgba alpha overlay on dark bg；text 用 light variants like #6FD8B0）。
 */
type RiskLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'

interface TierStyle {
  bg: string
  fg: string
  label: string
  tooltip?: string
}

const TIER_STYLES: Record<RiskLevel, TierStyle> = {
  NONE: {
    bg: 'rgba(29,158,117,0.14)',  // success-soft on dark
    fg: '#6FD8B0',                 // success-text light variant
    label: '無風險',
    tooltip: '掃描器未發現 known risk patterns。不代表 100% 安全，僅表示未抓到已知威脅指紋。',
  },
  LOW: {
    bg: 'rgba(55,138,221,0.14)',   // info-soft on dark
    fg: '#B0D5F2',                 // info-text
    label: '低風險',
  },
  MEDIUM: {
    bg: 'rgba(239,159,39,0.14)',   // warning-soft
    fg: '#FAC775',                 // warning-text
    label: '中風險',
  },
  HIGH: {
    bg: 'rgba(226,75,74,0.14)',    // danger-soft
    fg: '#F2A6A6',                 // danger-text
    label: '高風險',
  },
}

/**
 * 風險評估等級徽章元件。
 * @param level 風險等級字串（NONE/LOW/MEDIUM/HIGH），或 null（尚未評估）
 */
export function RiskBadge({ level }: { level: string | null }) {
  if (!level) return <Badge variant="secondary">未評估</Badge>
  const style = TIER_STYLES[level as RiskLevel]
  if (!style) {
    // 未知 tier (e.g., 將來加 CRITICAL) — graceful fallback 顯 raw level
    return <Badge variant="secondary">{level}</Badge>
  }
  return (
    <Badge
      variant="secondary"
      className="font-medium"
      style={{ backgroundColor: style.bg, color: style.fg }}
      title={style.tooltip}
    >
      {style.label}
    </Badge>
  )
}
