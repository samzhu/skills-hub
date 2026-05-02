import { Routes, Route } from 'react-router'
import { HomePage } from './pages/HomePage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { PublishPage } from './pages/PublishPage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { YourFirstSkillPage } from './pages/docs/YourFirstSkillPage'
import { MySkillsPage } from './pages/MySkillsPage'
import { SearchResultsPage } from './pages/SearchResultsPage'
import { LandingPage } from './pages/LandingPage'
import { RequestBoardPage } from './pages/RequestBoardPage'
import { CollectionsPage } from './pages/CollectionsPage'
import { NotificationsPage } from './pages/NotificationsPage'

function App() {
  return (
    <Routes>
      {/* S096e1: / 改為 Landing page (public 入口); /browse 為 authenticated browse */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/browse" element={<HomePage />} />
      {/* /skills 是 listing alias — 使用者輸入網址或書籤回鏈時的直覺路徑 */}
      <Route path="/skills" element={<HomePage />} />
      <Route path="/skills/:id" element={<SkillDetailPage />} />
      {/* S096c: canonical route per ADR-003；既有 :id alias 並行不破 */}
      <Route path="/skills/:author/:name" element={<SkillDetailPage />} />
      <Route path="/publish" element={<PublishPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      {/* S094a: 作者視角 dashboard — P6 SBE「作者查看自己的數據」 */}
      <Route path="/my-skills" element={<MySkillsPage />} />
      {/* S096g1: Request Board stub — read-only list；voting/claim 留 S096g2 */}
      <Route path="/requests" element={<RequestBoardPage />} />
      {/* S096f1: Collections stub — read-only list；install/create 留 S096f2 */}
      <Route path="/collections" element={<CollectionsPage />} />
      {/* S096h1: Notifications stub — read-only list + bell badge in AppShell */}
      <Route path="/notifications" element={<NotificationsPage />} />
      {/* S094b: 語意搜尋結果頁 — split inline → dedicated route */}
      <Route path="/search" element={<SearchResultsPage />} />
      {/* S094d: docs walkthrough — 第一篇 walkthrough；未來 /docs index 與其他 docs sub-routes 待後續 spec */}
      <Route path="/docs/your-first-skill" element={<YourFirstSkillPage />} />
      {/* unmatched URL 之前 render 空白 root，user 看不到 navbar 也沒 404 */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
