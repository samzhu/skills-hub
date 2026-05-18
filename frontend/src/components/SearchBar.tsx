import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { BeamFrame } from '@/components/BeamFrame'

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
 * S089: 改用自寫的 `BeamFrame`（取代 border-beam npm package）。
 *
 * 研究結論（S084 §2.2）：border-beam npm light theme 用 rgba(0,0,0,x) 黑色透明
 * inner-shadow，在 #FFFFFF 白背景物理上做不出 glow。S084 historical prototype
 * 規則已整理到 DESIGN.md；current page reference 是
 * `docs/grimo/ui/prototype/Skills Hub Homepage.html`。Search wrapper 直接 hand-roll
 * conic-gradient + 1px padding wrapper，與 DESIGN.md `card-featured` pattern 1:1 對齊。
 *
 * BeamFrame 1:1 port prototype CSS：transparent 0-300° → accent 紫 #7F77DD 330° →
 * info 藍 #378ADD 345° → transparent；4s rotation；1px padding 露 ring。
 */
export function SearchBar({ value, onChange }: SearchBarProps) {
  return (
    <BeamFrame>
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          placeholder="描述你想完成的任務或搜尋技能..."
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="h-12 rounded-lg pl-10 text-base border-none focus-visible:ring-0 focus-visible:ring-offset-0"
        />
      </div>
    </BeamFrame>
  )
}
