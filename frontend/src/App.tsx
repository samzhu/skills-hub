import { Routes, Route } from 'react-router'
import { HomePage } from './pages/HomePage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { PublishPage } from './pages/PublishPage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { YourFirstSkillPage } from './pages/docs/YourFirstSkillPage'
import { MySkillsPage } from './pages/MySkillsPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      {/* /skills 是 listing alias — 使用者輸入網址或書籤回鏈時的直覺路徑 */}
      <Route path="/skills" element={<HomePage />} />
      <Route path="/skills/:id" element={<SkillDetailPage />} />
      <Route path="/publish" element={<PublishPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      {/* S094a: 作者視角 dashboard — P6 SBE「作者查看自己的數據」 */}
      <Route path="/my-skills" element={<MySkillsPage />} />
      {/* S094d: docs walkthrough — 第一篇 walkthrough；未來 /docs index 與其他 docs sub-routes 待後續 spec */}
      <Route path="/docs/your-first-skill" element={<YourFirstSkillPage />} />
      {/* unmatched URL 之前 render 空白 root，user 看不到 navbar 也沒 404 */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
