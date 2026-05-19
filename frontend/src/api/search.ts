import { apiFetch } from './client'
import type { SemanticSearchResult, SpringSlice } from '@/types/skill'

export interface SemanticSearchParams {
  q: string
  page: number
  size: number
}

/**
 * 語意搜尋 API 函式 — 以自然語言查詢技能，回傳語意相似度排序的 Slice。
 *
 * 對應後端 GET /api/v1/search/semantic?q={query}&page={page}&size={size}。
 * 結果按 score 遞減排序；若無相關結果，後端回傳空 Slice（HTTP 200）。
 *
 * @param params.q 使用者輸入的自然語言查詢（如「幫我部署 Docker 容器應用」）
 * @param params.page 0-indexed 頁碼
 * @param params.size 每頁筆數
 * @returns 語意相關的技能 Slice（content 按 score 遞減）
 */
export function fetchSemanticSearch(
  { q, page, size }: SemanticSearchParams,
): Promise<SpringSlice<SemanticSearchResult>> {
  const params = new URLSearchParams({
    q,
    page: String(page),
    size: String(size),
  })
  return apiFetch<SpringSlice<SemanticSearchResult>>(`/search/semantic?${params.toString()}`)
}
