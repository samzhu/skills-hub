import { apiFetch } from './client'
import type { SemanticSearchResult } from '@/types/skill'

/**
 * 語意搜尋 API 函式 — 以自然語言查詢技能，回傳語意相似度排序的結果清單。
 *
 * 對應後端 GET /api/v1/search/semantic?q={query}。
 * 結果按 score 遞減排序；若無相關結果，後端回傳空陣列（HTTP 200）。
 *
 * @param query 使用者輸入的自然語言查詢（如「幫我部署 Docker 容器應用」）
 * @returns 語意相關的技能清單（按 score 遞減）
 */
export function fetchSemanticSearch(query: string): Promise<SemanticSearchResult[]> {
  // encodeURIComponent 確保中文及特殊字元正確編碼至查詢參數
  return apiFetch<SemanticSearchResult[]>(`/search/semantic?q=${encodeURIComponent(query)}`)
}

/**
 * S094b — Intent summary API.
 *
 * Backend POST /api/v1/search/intent with `{query}`. Returns LLM-generated intent
 * summary (繁體中文 1-sentence) + concept tags (English keywords). When LLM is
 * unavailable, backend graceful-fallbacks to `{summary: query, concepts: []}`.
 * Frontend can detect fallback by `concepts.length === 0`.
 */
export interface IntentResponse {
  summary: string
  concepts: string[]
}

export function fetchSearchIntent(query: string): Promise<IntentResponse> {
  return apiFetch<IntentResponse>('/search/intent', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  })
}
