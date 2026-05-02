import { useSearchParams, Link } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { SearchBar } from '@/components/SearchBar'
import { SkillCard } from '@/components/SkillCard'
import { EmptyState } from '@/components/EmptyState'
import { IntentSummaryCard } from '@/components/IntentSummaryCard'
import { useSemanticSearch } from '@/hooks/useSemanticSearch'
import { useSearchIntent } from '@/hooks/useSearchIntent'
import { useState } from 'react'
import { useNavigate } from 'react-router'

/**
 * S094b — Dedicated `/search?q=...` semantic search results page.
 *
 * 對齊 docs/grimo/ui/prototype/semantic_search_results_page.html (README ll.111-133).
 *
 * 設計重點:
 * - Search bar with semantic mode dot pulse
 * - AI intent summary card (purple #EEEDFE) — 顯示「Understood your intent」+ concept chips
 *   * Backend graceful fallback：若 LLM 未啟用，concepts.length === 0 → 不顯 card
 * - Result list 用 SkillCard with similarity score
 * - 0 results → EmptyState redirect tone (S094c reuse)
 *
 * Trim from prototype 完整版 (S 收斂):
 * - Concept chip × interactivity (remove) — display-only
 * - Per-result why-match reasoning — deferred (避免 7+ LLM calls/search)
 * - Top match gradient background + 0.94 score badge — uniform display
 * - Refine chips 4 items at bottom — defer; user 自己 re-search
 */
export function SearchResultsPage() {
  const [searchParams] = useSearchParams()
  const query = searchParams.get('q') ?? ''
  const navigate = useNavigate()

  const { data: results, isLoading: resultsLoading, error } = useSemanticSearch(query)
  const { data: intent } = useSearchIntent(query)

  // Local query input state — search bar 立即更新，但 commit 走 navigate 以維持 URL = source of truth
  const [pendingQuery, setPendingQuery] = useState(query)

  const handleSearch = (q: string) => {
    if (q.trim().length === 0) {
      navigate('/')
    } else {
      navigate(`/search?q=${encodeURIComponent(q)}`)
    }
  }

  return (
    <AppShell>
      <form
        className="mb-4"
        onSubmit={(e) => {
          e.preventDefault()
          handleSearch(pendingQuery)
        }}
      >
        <SearchBar value={pendingQuery} onChange={setPendingQuery} />
      </form>

      {!query.trim() ? (
        <EmptyState
          tone="invite"
          headline="輸入一句描述或關鍵字搜尋技能。"
          sub="語意模式會用 AI 解析你的意圖，找最匹配的 skill。也可以從首頁瀏覽全部分類。"
          primaryAction={{ label: '瀏覽全部技能', href: '/' }}
        />
      ) : resultsLoading ? (
        <div className="flex items-center justify-center py-16 text-muted-foreground">語意分析中...</div>
      ) : error ? (
        <div className="flex items-center justify-center py-16 text-red-500">
          搜尋失敗，請重試
        </div>
      ) : (
        <>
          {/* AI intent summary — only show when concepts present (LLM available) */}
          {intent && intent.concepts.length > 0 && (
            <IntentSummaryCard summary={intent.summary} concepts={intent.concepts} />
          )}

          {/* Results meta */}
          <div className="mb-4 flex items-baseline justify-between text-[12px] text-muted-foreground">
            <span>
              找到 <strong className="font-mono text-foreground">{results?.length ?? 0}</strong> 個相關技能
            </span>
            <span className="font-mono text-[11px]">ranked by semantic similarity · embeddings via Gemini</span>
          </div>

          {/* Results */}
          {!results || results.length === 0 ? (
            <EmptyState
              tone="redirect"
              query={query}
              headline="這個描述還沒有匹配的技能。"
              sub="現有技能與你描述的概念相似度都偏低。可以調整描述、改用關鍵字模式、或邀請團隊發布。"
              suggestions={[
                { text: '回首頁瀏覽全部技能', hint: '取消當前過濾' },
                { text: '改用更直接的關鍵字', hint: '把動詞拉到開頭' },
                { text: '發布這個技能', hint: '可能你的團隊也需要這個' },
              ]}
            />
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              {results.map((r, i) => (
                <SkillCard
                  key={r.id}
                  // SemanticSearchResult 與 Skill 欄位高度重疊；S094b 仍用既有 SkillCard
                  skill={r as unknown as Parameters<typeof SkillCard>[0]['skill']}
                  score={r.score}
                  // S096d2: 第一個 result = top match → featured beam ring per Handoff §8
                  // (1-per-page rule already met — 沒其他 BeamFrame on this page)
                  featured={i === 0}
                />
              ))}
            </div>
          )}

          {/* Footer hint — link to docs */}
          <div className="mt-8 border-t border-border pt-4 text-center text-[11.5px] text-muted-foreground">
            想知道為什麼用語意搜尋？ <Link to="/docs/your-first-skill" className="underline-offset-2 hover:underline">看 description writing tips →</Link>
          </div>
        </>
      )}
    </AppShell>
  )
}
