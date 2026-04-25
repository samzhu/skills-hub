import { useState } from 'react'
import { AppShell } from '@/components/AppShell'
import { SearchBar } from '@/components/SearchBar'
import { CategorySidebar } from '@/components/CategorySidebar'
import { SkillCardGrid } from '@/components/SkillCardGrid'
import { useSkillList } from '@/hooks/useSkillList'
import { useCategories } from '@/hooks/useCategories'

/**
 * 技能瀏覽首頁：包含關鍵字搜尋列、分類側邊欄、技能卡片網格及分頁控制列。
 *
 * 搜尋與分類篩選條件儲存於元件 state，
 * 任一條件改變時同步重置頁碼至第 0 頁，避免顯示無效頁面。
 */
export function HomePage() {
  const [keyword, setKeyword] = useState('')
  const [category, setCategory] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  const { data: skillsPage, isLoading, error } = useSkillList({
    // 空字串轉為 undefined，避免後端收到 keyword= 空值而誤判為有效關鍵字
    keyword: keyword || undefined,
    // null 表示「不篩選分類」，轉為 undefined 以省略查詢參數
    category: category ?? undefined,
    page,
    size: 20,
  })

  const { data: categories } = useCategories()

  /**
   * 使用者輸入關鍵字時觸發，同時重置頁碼至第 0 頁，
   * 避免搜尋條件改變後停留在已無效的頁面。
   */
  const handleSearch = (value: string) => {
    setKeyword(value)
    setPage(0)
  }

  /**
   * 使用者點選側邊欄分類時觸發；傳入 null 表示取消篩選（顯示全部）。
   * 同樣重置頁碼，確保從第一頁開始瀏覽新篩選結果。
   */
  const handleCategorySelect = (cat: string | null) => {
    setCategory(cat)
    setPage(0)
  }

  return (
    <AppShell>
      <div className="mb-6">
        <h1 className="mb-4 text-2xl font-bold">探索 Agent 技能</h1>
        <SearchBar value={keyword} onChange={handleSearch} />
      </div>

      <div className="flex gap-6">
        <aside className="hidden w-56 shrink-0 md:block">
          <CategorySidebar
            categories={categories ?? []}
            selected={category}
            onSelect={handleCategorySelect}
          />
        </aside>

        <div className="min-w-0 flex-1">
          {isLoading ? (
            <div className="flex items-center justify-center py-16 text-muted-foreground">
              載入中...
            </div>
          ) : error ? (
            // 查詢失敗時顯示明確錯誤訊息，錯誤已由 main.tsx QueryCache 訂閱記錄至 console
            <div className="flex items-center justify-center py-16 text-red-500">
              載入技能失敗，請重新整理頁面
            </div>
          ) : (
            <>
              <div className="mb-4 text-sm text-muted-foreground">
                共 {skillsPage?.page.totalElements ?? 0} 個技能
              </div>
              <SkillCardGrid skills={skillsPage?.content ?? []} />
              {skillsPage && skillsPage.page.totalPages > 1 && (
                <div className="mt-6 flex items-center justify-center gap-2">
                  <button
                    onClick={() => setPage(Math.max(0, page - 1))}
                    disabled={page === 0}
                    className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-50"
                  >
                    上一頁
                  </button>
                  <span className="text-sm text-muted-foreground">
                    第 {page + 1} / {skillsPage.page.totalPages} 頁
                  </span>
                  <button
                    onClick={() => setPage(Math.min(skillsPage.page.totalPages - 1, page + 1))}
                    disabled={page >= skillsPage.page.totalPages - 1}
                    className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-50"
                  >
                    下一頁
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </AppShell>
  )
}
