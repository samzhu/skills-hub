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
 * S098d 排序模式 — frontend mode 對應 backend `?sort=field,direction`。
 * S106: "推薦" 暫 = downloadCount,desc（與 "下載最多" 同 mapping，但 UX chip 仍
 * distinct，future evolve 為 recommendation algorithm 時改 mapping 即可）；
 * backend default 實為 createdAt DESC（per SkillQueryService.search fallback）—
 * 不能依賴 fall-through 到 default，必須 explicit param。
 */
type SortMode = 'recommended' | 'newest' | 'risk-low' | 'most-downloaded'
const SORT_LABELS: Record<SortMode, string> = {
  recommended: '推薦',
  newest: '最新',
  'risk-low': '風險低',
  'most-downloaded': '下載最多',
}
// S104: zh-TW labels for filter-active EmptyState headline
const RISK_TIER_LABELS: Record<RiskLevel, string> = {
  NONE: '無風險',
  LOW: '低風險',
  MEDIUM: '中風險',
  HIGH: '高風險',
}
// S100b: RISK_ORDER 移除 — risk-low sort 改 server-side（backend ORDER BY risk_level ASC
// 字典序 NONE→LOW→MEDIUM→HIGH 與此 enum 順序一致）。

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
  // S100b: sort param 改 server-side 派遣 — backend Spring Pageable 接收 sort=field,direction
  const { data: skillsPage, isLoading: listLoading, error: listError } = useSkillList({
    keyword: query.trim() || undefined,
    category: category ?? undefined,
    page,
    size: 20,
    sort: sortMode,
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

  // S100b: sort 改 server-side（query param sort=field,direction）— 跨頁全域 sort
  // 保留 client-side risk-filter — 因 backend 沒有 multi-tier filter 支援（不在本 spec scope）
  const filteredSkills = useMemo(() => {
    const content: Skill[] = skillsPage?.content ?? []
    if (riskFilter.size === 0) return content
    return content.filter((s) => s.riskLevel && riskFilter.has(s.riskLevel))
  }, [skillsPage, riskFilter])

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
                  {/* S104: filter active → show filtered count + total context；no filter → 既有 unfiltered total */}
                  {riskFilter.size > 0 ? (
                    <>{filteredSkills.length} 個技能（共 {skillsPage?.page.totalElements ?? 0}）</>
                  ) : (
                    <>共 {skillsPage?.page.totalElements ?? 0} 個技能</>
                  )}
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
              {/* S104: filter-active + 0 hits → context-aware redirect tone with 清除篩選 escape hatch
                  避免 generic seed-empty「技能庫等著被開啟」誤導（registry 實際非空，只是 filter 過濾掉） */}
              {filteredSkills.length === 0 && riskFilter.size > 0 ? (
                <EmptyState
                  tone="redirect"
                  headline={`沒有「${[...riskFilter].map((t) => RISK_TIER_LABELS[t]).join('、')}」的技能`}
                  sub={`目前沒有符合此風險篩選的技能。試試其他風險等級或清除篩選看全部 ${skillsPage?.page.totalElements ?? 0} 個技能。`}
                  primaryAction={{ label: '清除篩選', onClick: () => setRiskFilter(new Set()) }}
                />
              ) : (
                <SkillCardGrid skills={filteredSkills} query={query} />
              )}
              {/* S104: filter active 且 filteredSkills 0 時 hide pagination — 跨頁仍 0 hits 沒意義 */}
              {skillsPage && skillsPage.page.totalPages > 1 && filteredSkills.length > 0 && (
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
