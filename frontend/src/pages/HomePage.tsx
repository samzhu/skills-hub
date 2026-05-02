import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { SearchBar } from '@/components/SearchBar'
import { CategorySidebar } from '@/components/CategorySidebar'
import { RiskFilterSidebar } from '@/components/RiskFilterSidebar'
import { SkillCard } from '@/components/SkillCard'
import { SkillCardGrid } from '@/components/SkillCardGrid'
import { EmptyState } from '@/components/EmptyState'
import { useSkillList } from '@/hooks/useSkillList'
import { useCategories } from '@/hooks/useCategories'
import { useSemanticSearch } from '@/hooks/useSemanticSearch'
import type { RiskLevel, Skill } from '@/types/skill'

/**
 * S098d 排序模式（client-side sort，不打 backend 額外 query）。
 * 因 backend `/skills` 預設 downloadCount desc，"推薦"= identity，無需轉換。
 */
type SortMode = 'recommended' | 'newest' | 'risk-low' | 'most-downloaded'
const SORT_LABELS: Record<SortMode, string> = {
  recommended: '推薦',
  newest: '最新',
  'risk-low': '風險低',
  'most-downloaded': '下載最多',
}
const RISK_ORDER: Record<Skill['riskLevel'], number> = {
  NONE: 0,
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
}

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
  const [sortMode, setSortMode] = useState<SortMode>('recommended')
  // S098d2: risk filter selection — empty Set = 「不篩選」= 全顯
  const [riskFilter, setRiskFilter] = useState<Set<RiskLevel>>(new Set())

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

  // 語意搜尋模式：query 非空、semantic 未 error、且確實有結果時啟用。
  // S046：semantic 回空（dev 未配置 embedding / prod 真 zero match / silent failure）
  // 也算 fallback 觸發條件 — 落 keyword mode，避免「找到 0 個 試試換個描述方式」死巷。
  const isSemanticMode = query.trim().length > 0
    && !semanticError
    && (semanticResults?.length ?? 0) > 0

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

  // S098d: client-side sort + S098d2: client-side risk-filter — 兩階管線都基於 backend 該頁回的 content。
  // Trade-off：filter 與 sort「只在當前頁」生效。後續若要全域 → 後端 sort/filter query。
  const filteredAndSorted = useMemo(() => {
    let content: Skill[] = skillsPage?.content ?? []
    // 1. risk filter — empty Set = pass-through
    if (riskFilter.size > 0) {
      content = content.filter((s) => s.riskLevel && riskFilter.has(s.riskLevel))
    }
    // 2. sort
    if (sortMode === 'recommended') return content
    const arr = [...content]
    if (sortMode === 'newest') {
      arr.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
    } else if (sortMode === 'risk-low') {
      arr.sort((a, b) => RISK_ORDER[a.riskLevel] - RISK_ORDER[b.riskLevel])
    } else if (sortMode === 'most-downloaded') {
      arr.sort((a, b) => (b.downloadCount ?? 0) - (a.downloadCount ?? 0))
    }
    return arr
  }, [skillsPage, sortMode, riskFilter])

  // S098d2 toggle / clear
  const toggleRisk = (level: RiskLevel) => {
    setRiskFilter((prev) => {
      const next = new Set(prev)
      if (next.has(level)) next.delete(level)
      else next.add(level)
      return next
    })
  }

  return (
    <AppShell>
      {/* S085: hero row — H1 + sub-text + 「發布技能」 primary CTA per prototype `.sh-hero-row` */}
      <div className="mb-[14px] flex items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-[22px] font-medium leading-[1.2]">探索 Agent 技能</h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            為團隊發現、評估與安裝可信任的 AI agent 技能
          </p>
        </div>
        <Link
          to="/publish"
          className="inline-flex items-center whitespace-nowrap rounded-md bg-primary px-3.5 py-2 text-[13px] font-medium text-primary-foreground hover:bg-foreground"
        >
          發布技能
        </Link>
      </div>

      <div className="mb-[14px]">
        <SearchBar value={query} onChange={handleSearch} />
      </div>

      <div className="flex gap-6">
        {/* CategorySidebar + RiskFilterSidebar 只在關鍵字搜尋模式（query 空）時顯示 */}
        {!isSemanticMode && (
          <aside className="hidden w-56 shrink-0 md:block space-y-6">
            {/* S098d2: risk filter — client-side counts 來自當前頁 skillsPage.content */}
            <RiskFilterSidebar
              skills={skillsPage?.content ?? []}
              selected={riskFilter}
              onToggle={toggleRisk}
              onClear={() => setRiskFilter(new Set())}
            />
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
                // S094c: replace inline 0-results with EmptyState redirect tone — query echo + suggestions
                <EmptyState
                  tone="redirect"
                  query={query}
                  headline="這個描述還沒有匹配的技能。"
                  sub="現有技能與你描述的概念相似度都偏低。可以調整描述、改用關鍵字模式，或邀請團隊發布。"
                  suggestions={[
                    { text: '改用關鍵字搜尋', hint: '更直接的詞彙比喻；trim fallback 仍會回所有技能' },
                    { text: '換個描述方式', hint: '把技能要做的「動詞」拉到開頭' },
                    { text: '發布這個技能', hint: '你可能是第一個遇到此需求的人' },
                  ]}
                />
              )}
            </>
          ) : (
            // 關鍵字搜尋 / 全部瀏覽模式：含分頁
            <>
              <div className="mb-4 flex items-center justify-between gap-4">
                <span className="text-sm text-muted-foreground">
                  共 {skillsPage?.page.totalElements ?? 0} 個技能
                </span>
                {/* S098d: sort chips per prototype `.sort-chips` (4 modes 推薦/最新/風險低/下載最多) */}
                <div className="flex gap-1">
                  {(Object.keys(SORT_LABELS) as SortMode[]).map((mode) => {
                    const isOn = sortMode === mode
                    return (
                      <button
                        key={mode}
                        type="button"
                        onClick={() => setSortMode(mode)}
                        className={
                          'rounded-full px-2.5 py-1 text-[12px] transition-colors ' +
                          (isOn
                            ? 'border border-[rgba(255,255,255,0.10)] bg-[rgba(255,255,255,0.06)] font-medium text-foreground'
                            : 'border border-transparent text-muted-foreground hover:text-foreground')
                        }
                      >
                        {SORT_LABELS[mode]}
                      </button>
                    )
                  })}
                </div>
              </div>
              {/* S094c: pass query so 0-results can show seed (no query) vs redirect (with query) tone */}
              <SkillCardGrid skills={filteredAndSorted} query={query} />
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
