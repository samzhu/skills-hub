import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App'
import { ApiError } from '@/api/client'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 30 秒內不重新請求同一份資料，減少 SPA 頁面切換時的 API 呼叫。
      // 技能列表與分類資料更新頻率低，此設定在可接受的延遲範圍內。
      staleTime: 30_000,
      // S065 revised: 4xx 不 retry — user 錯誤（404 not-found / 400 validation / 409 conflict）
      // 重試無意義；retry backoff 期 React Query 進入 fetchStatus='paused' 觀察到 hang bug。
      // 5xx / network 仍重試一次（短暫中斷救援）。
      retry: (failureCount, error) => {
        if (ApiError.is(error) && error.status >= 400 && error.status < 500) return false
        return failureCount < 1
      },
      // S065: networkMode 'always' — 預設 'online' 在 navigator.onLine 偶發判斷錯時
      // 把 query 鎖在 fetchStatus='paused'。'always' 永遠 fetch — SPA dev / 部署環境同 host 場景下更可靠。
      networkMode: 'always',
    },
  },
})

// 全域 query 錯誤 logging：React Query v5 移除了 useQuery 的 onError callback，
// 改用 QueryCache 訂閱機制在這裡統一記錄未被 UI 處理的 query 錯誤。
// S064: 跳過 4xx ApiError — UI 已負責處理（404 not-found 顯示友善 state per S039；
// 400/409 等顯 i18n banner per S040）。保留 5xx / network / 非 ApiError 錯誤 log
// 利 dev 找真問題；console pollution 大幅降低。
queryClient.getQueryCache().subscribe((event) => {
  if (event.type === 'updated' && event.action.type === 'error') {
    const err = event.action.error
    // S065: ApiError.is name-based check — HMR 安全；instanceof 在 module 重載後不一定可靠
    if (ApiError.is(err) && err.status >= 400 && err.status < 500) {
      return
    }
    console.error('[QueryCache]', event.query.queryKey, err)
  }
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
