import { useQuery } from '@tanstack/react-query'
import { fetchCategories } from '../api/skills'

/**
 * 取得所有技能分類清單的 React Query hook。
 *
 * 快取鍵為 `['categories']`，分類資料不常變動，staleTime（30s）
 * 足以避免重複請求。查詢錯誤透過 main.tsx QueryCache 訂閱統一記錄。
 *
 * @returns React Query 查詢結果，包含 data（CategoryCount[]）、isLoading、error
 */
export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: fetchCategories,
  })
}
