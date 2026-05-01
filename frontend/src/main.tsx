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
      // 只重試一次：後端錯誤（4xx）不需重試，網路短暫中斷一次重試即可。
      // React Query 預設為 3 次，這裡降低以縮短使用者等待時間。
      retry: 1,
      // S065: networkMode 'always' — 預設 'online' 在 navigator.onLine 偶發判斷錯時
      // 把 query 鎖在 fetchStatus='paused'，導致 SkillDetailPage 進入「!skill && error=null」
      // 異常分支顯示「載入錯誤」而非 friendly 404 state。'always' 永遠 fetch，failure 走錯誤分支
      // —— SPA 對 API 必達的場景（dev / 部署環境同 host）此設定更可靠。
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
