import { Routes, Route } from 'react-router'
import { HomePage } from './pages/HomePage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { PublishPage } from './pages/PublishPage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { NotFoundPage } from './pages/NotFoundPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      {/* /skills 是 listing alias — 使用者輸入網址或書籤回鏈時的直覺路徑 */}
      <Route path="/skills" element={<HomePage />} />
      <Route path="/skills/:id" element={<SkillDetailPage />} />
      <Route path="/publish" element={<PublishPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      {/* unmatched URL 之前 render 空白 root，user 看不到 navbar 也沒 404 */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
