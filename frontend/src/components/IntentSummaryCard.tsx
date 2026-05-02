import { Sparkles } from 'lucide-react'

/**
 * S094b — AI intent summary card 對齊 prototype `semantic_search_results_page.html`：
 * - 紫底 (rgba(127,119,221,0.18)) + ✦ sparkle icon + "Understood your intent" label
 * - 1 段繁中 intent summary
 * - 4 個 concept chip（display-only；prototype × 互動性 deferred 至 future polish）
 *
 * 不顯時機：concepts 為空（backend graceful fallback：LLM unavailable）— 此時為純 keyword
 * search 行為不該假裝有 AI 解釋。Caller 透過 concepts.length 判斷。
 */
export function IntentSummaryCard({
  summary,
  concepts,
}: {
  summary: string
  concepts: string[]
}) {
  return (
    <div
      className="mb-5 rounded-lg border border-[#D8D4FA] p-4"
      style={{ backgroundColor: 'rgba(127,119,221,0.18)' }}
    >
      <div className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-[#C9C5F2]">
        <Sparkles className="h-3 w-3" />
        Understood your intent
      </div>
      <p className="text-[14px] leading-relaxed text-[#181818]">{summary}</p>
      {concepts.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {concepts.map((c, i) => (
            <span
              key={i}
              className="inline-flex items-center rounded-full border border-[#D8D4FA] bg-white px-2.5 py-1 font-mono text-[11.5px] text-[#C9C5F2]"
            >
              {c}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
