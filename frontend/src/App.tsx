import { Routes, Route } from 'react-router'
import { HomePage } from './pages/HomePage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { PublishPage } from './pages/PublishPage'
import { AnalyticsPage } from './pages/AnalyticsPage'

/**
 * 根路由元件，以 React Router v6 `<Routes>` 宣告四個頁面的路徑對應。
 * 此元件不含任何狀態，純粹作為路由配置的入口點。
 */
function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/skills/:id" element={<SkillDetailPage />} />
      <Route path="/publish" element={<PublishPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
    </Routes>
  )
}

export default App
