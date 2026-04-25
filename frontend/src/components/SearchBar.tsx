import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { BorderBeam } from 'border-beam'

/** SearchBar 的 props 定義 */
interface SearchBarProps {
  /** 目前的搜尋關鍵字（受控元件，由父元件管理狀態） */
  value: string
  /** 使用者輸入時的回呼，傳入最新的輸入字串 */
  onChange: (value: string) => void
}

/**
 * 搜尋列元件：包含前置搜尋 icon 的文字輸入框，外層包覆 `BorderBeam` 光暈效果。
 *
 * 使用 `type="search"` 以啟用瀏覽器內建的清除按鈕（X）；
 * 父元件的 onChange 已處理清空邏輯（回傳空字串），兩者互不衝突。
 *
 * `BorderBeam` 是 border-beam 套件提供的裝飾性動畫外框，
 * 純視覺效果，不影響元件行為。
 */
export function SearchBar({ value, onChange }: SearchBarProps) {
  return (
    <BorderBeam>
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          placeholder="搜尋技能名稱或描述..."
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="h-12 rounded-lg pl-10 text-base"
        />
      </div>
    </BorderBeam>
  )
}
