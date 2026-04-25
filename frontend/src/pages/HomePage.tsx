import { useState } from 'react'
import { AppShell } from '@/components/AppShell'
import { SearchBar } from '@/components/SearchBar'
import { CategorySidebar } from '@/components/CategorySidebar'
import { SkillCard } from '@/components/SkillCard'
import { SkillCardGrid } from '@/components/SkillCardGrid'
import { useSkillList } from '@/hooks/useSkillList'
import { useCategories } from '@/hooks/useCategories'
import { useSemanticSearch } from '@/hooks/useSemanticSearch'

/**
 * 技能瀏覽首頁：支援語意搜尋（自然語言）與關鍵字搜尋（fallback）。
 *
 * 搜尋模式切換規則：
 * - query 非空 → 語意搜尋模式（useSemanticSearch），隱藏 CategorySidebar 與分頁
 * - query 空字串 → 關鍵字搜尋模式（useSkillList），顯示完整 CategorySidebar 與分頁
 *
 * 此設計符合 AC-6：「關鍵字搜尋與分類篩選在語意搜尋模式下隱藏」。
 */
export function HomePage() {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  // 語意搜尋模式：query 非空時啟用（enabled 由 hook 內部控制）
  const {
    data: semanticResults,
    isLoading: semanticLoading,
    error: semanticError,
  } = useSemanticSearch(query)

  // 關鍵字搜尋模式：query 空時為主模式；query 非空時作為語意搜尋的 fallback
  const { data: skillsPage, isLoading: listLoading, error: listError } = useSkillList({
    keyword: query.trim() || undefined,
    category: category ?? undefined,
    page,
    size: 20,
  })

  const { data: categories } = useCategories()

  // 語意搜尋模式：query 非空且語意搜尋未出錯時啟用；出錯時退回關鍵字搜尋
  const isSemanticMode = query.trim().length > 0 && !semanticError

  /**
   * 使用者輸入搜尋字串時觸發，同時重置分頁與分類篩選。
   */
  const handleSearch = (value: string) => {
    setQuery(value)
    setPage(0)
  }

  /**
   * 點選側邊欄分類時觸發；只在關鍵字模式下有效。
   */
  const handleCategorySelect = (cat: string | null) => {
    setCategory(cat)
    setPage(0)
  }

  const isLoading = isSemanticMode ? semanticLoading : listLoading
  const error = isSemanticMode ? semanticError : listError

  return (
    <AppShell>
      <div className="mb-6">
        <h1 className="mb-4 text-2xl font-bold">探索 Agent 技能</h1>
        <SearchBar value={query} onChange={handleSearch} />
      </div>

      <div className="flex gap-6">
        {/* CategorySidebar 只在關鍵字搜尋模式（query 空）時顯示 */}
        {!isSemanticMode && (
          <aside className="hidden w-56 shrink-0 md:block">
            <CategorySidebar
              categories={categories ?? []}
              selected={category}
              onSelect={handleCategorySelect}
            />
          </aside>
        )}

        <div className="min-w-0 flex-1">
          {isLoading ? (
            <div className="flex items-center justify-center py-16 text-muted-foreground">
              載入中...
            </div>
          ) : error ? (
            <div className="flex items-center justify-center py-16 text-red-500">
              載入技能失敗，請重新整理頁面
            </div>
          ) : isSemanticMode ? (
            // 語意搜尋結果：顯示每張卡片的相符度 badge，無分頁
            <>
              <div className="mb-4 text-sm text-muted-foreground">
                找到 {semanticResults?.length ?? 0} 個相關技能
              </div>
              {semanticResults && semanticResults.length > 0 ? (
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  {semanticResults.map((result) => (
                    <SkillCard
                      key={result.id}
                      // SemanticSearchResult 欄位與 Skill 高度重疊；
                      // 以型別斷言橋接（status / createdAt / updatedAt 為語意結果不含的欄位）
                      skill={result as unknown as Parameters<typeof SkillCard>[0]['skill']}
                      score={result.score}
                    />
                  ))}
                </div>
              ) : (
                <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
                  <p className="text-lg font-medium">未找到匹配的技能</p>
                  <p className="text-sm">試試換個描述方式</p>
                </div>
              )}
            </>
          ) : (
            // 關鍵字搜尋 / 全部瀏覽模式：含分頁
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
