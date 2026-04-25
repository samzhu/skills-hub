import { Card, CardContent } from '@/components/ui/card'

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
 * 單一指標卡片：以大字體顯示一個數值及其標籤。
 * 常用於儀表板頁面的概覽統計區塊。
 */
export function MetricCard({ label, value, subtitle }: MetricCardProps) {
  return (
    <Card>
      <CardContent className="pt-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="text-2xl font-bold">{value}</p>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </CardContent>
    </Card>
  )
}
