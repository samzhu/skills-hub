import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 30 秒內不重新請求同一份資料，減少 SPA 頁面切換時的 API 呼叫。
      // 技能列表與分類資料更新頻率低，此設定在可接受的延遲範圍內。
      staleTime: 30_000,
      // 只重試一次：後端錯誤（4xx）不需重試，網路短暫中斷一次重試即可。
      // React Query 預設為 3 次，這裡降低以縮短使用者等待時間。
      retry: 1,
    },
  },
})

// 全域 query 錯誤 logging：React Query v5 移除了 useQuery 的 onError callback，
// 改用 QueryCache 訂閱機制在這裡統一記錄所有未被 UI 處理的 query 錯誤。
queryClient.getQueryCache().subscribe((event) => {
  if (event.type === 'updated' && event.action.type === 'error') {
    console.error('[QueryCache]', event.query.queryKey, event.action.error)
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
