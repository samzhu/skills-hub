import { cn } from '@/lib/utils'
import { capitalize } from '@/lib/text'
import type { CategoryCount } from '@/types/skill'

/** CategorySidebar 的 props 定義 */
interface CategorySidebarProps {
  /** 所有分類及其數量（由 useCategories hook 取得） */
  categories: CategoryCount[]
  /** 目前選中的分類名稱；null 表示「全部」（不篩選） */
  selected: string | null
  /**
   * 使用者點選分類時的回呼。
   * 傳入 null 代表取消篩選（顯示全部技能）。
   */
  onSelect: (category: string | null) => void
}

/**
 * 技能分類側邊欄：顯示「全部」按鈕及各分類列表，支援單選篩選。
 *
 * 「全部」按鈕旁顯示所有分類的技能數加總。
 * 此計算假設每個技能只屬於一個分類（後端保證），因此加總等於平台技能總數。
 */
export function CategorySidebar({ categories, selected, onSelect }: CategorySidebarProps) {
  // 各分類數量加總，用於「全部」按鈕旁的計數顯示
  const total = categories.reduce((sum, c) => sum + c.count, 0)

  return (
    <div className="space-y-1">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        分類
      </h3>
      {/* null 作為「不篩選」的 sentinel 值，點擊「全部」時清除分類篩選 */}
      <button
        onClick={() => onSelect(null)}
        className={cn(
          'flex w-full items-center justify-between rounded-md px-3 py-1.5 text-sm',
          selected === null ? 'bg-accent font-medium' : 'hover:bg-accent/50'
        )}
      >
        <span>全部</span>
        <span className="text-xs text-muted-foreground">{total}</span>
      </button>
      {categories.map((cat) => (
        <button
          key={cat.name}
          onClick={() => onSelect(cat.name)}
          className={cn(
            'flex w-full items-center justify-between rounded-md px-3 py-1.5 text-sm',
            selected === cat.name ? 'bg-accent font-medium' : 'hover:bg-accent/50'
          )}
        >
          <span>{capitalize(cat.name)}</span>
          <span className="text-xs text-muted-foreground">{cat.count}</span>
        </button>
      ))}
    </div>
  )
}
