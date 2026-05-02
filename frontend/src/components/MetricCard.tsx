/** MetricCard 的 props 定義 */
interface MetricCardProps {
  /** 指標名稱（顯示於數值上方的小標籤） */
  label: string
  /** 指標數值（字串或數字，直接渲染） */
  value: string | number
  /** 選填的補充說明，顯示於數值下方 */
  subtitle?: string
}

/**
 * S088: 重寫對齊 prototype `platform_analytics_dashboard_admin_view.html` `.sh-metric` 結構。
 *
 * - hairline border + 14px padding + lg radius (per `.sh-card`)
 * - label 11px caps + accent-mid color (一致整 dashboard label-caps 風格)
 * - value 22px medium foreground
 * - subtitle 11px tertiary
 * - DESIGN.md mono-xs label-caps style：letter-spacing 0.04em + uppercase
 */
export function MetricCard({ label, value, subtitle }: MetricCardProps) {
  return (
    <div className="rounded-lg border border-border bg-card p-[14px]">
      <p className="m-0 text-[11px] font-medium uppercase tracking-[0.05em] text-muted-foreground">
        {label}
      </p>
      <p className="m-0 mt-1.5 text-[22px] font-medium leading-[1.2] text-foreground">
        {value}
      </p>
      {subtitle && (
        <p className="m-0 mt-0.5 text-[11px] text-muted-foreground">{subtitle}</p>
      )}
    </div>
  )
}
