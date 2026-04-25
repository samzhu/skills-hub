import { useQuery } from '@tanstack/react-query'
import { fetchOverview } from '../api/analytics'

/**
 * 取得平台概覽統計資料的 React Query hook。
 *
 * 快取鍵為 `['analytics', 'overview']`，staleTime 使用全域設定（30s）。
 * 查詢錯誤會透過 main.tsx 的 QueryCache 訂閱統一 log 至 console。
 *
 * @returns React Query 查詢結果，包含 data（OverviewStats）、isLoading、error
 */
export function useOverview() {
  return useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: fetchOverview,
  })
}
