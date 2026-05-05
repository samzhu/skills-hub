import { Routes, Route } from 'react-router'
import { HomePage } from './pages/HomePage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { PublishPage } from './pages/PublishPage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { YourFirstSkillPage } from './pages/docs/YourFirstSkillPage'
import { OverviewPage } from './pages/docs/OverviewPage'
import { RiskTiersPage } from './pages/docs/RiskTiersPage'
import { SkillMdSpecPage } from './pages/docs/SkillMdSpecPage'
import { FrontmatterPage } from './pages/docs/FrontmatterPage'
import { BundleStructurePage } from './pages/docs/BundleStructurePage'
import { UploadValidatePage } from './pages/docs/UploadValidatePage'
import { VersioningPage } from './pages/docs/VersioningPage'
import { SemanticSearchPage } from './pages/docs/SemanticSearchPage'
import { RestApiPage } from './pages/docs/RestApiPage'
import { EventPayloadPage } from './pages/docs/EventPayloadPage'
import { RiskScannerScopePage } from './pages/docs/RiskScannerScopePage'
import { MySkillsPage } from './pages/MySkillsPage'
import { SearchResultsPage } from './pages/SearchResultsPage'
import { LandingPage } from './pages/LandingPage'
import { RequestBoardPage } from './pages/RequestBoardPage'
import { CollectionsPage } from './pages/CollectionsPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { PublishReviewPage } from './pages/PublishReviewPage'
import { PublishFailedPage } from './pages/PublishFailedPage'
import { PublishValidatePage } from './pages/PublishValidatePage'
import { VersionDiffPage } from './pages/VersionDiffPage'
import { FlagsQueuePage } from './pages/FlagsQueuePage'
import { AuthDebugPage } from './pages/AuthDebugPage'

function App() {
  return (
    <Routes>
      {/* S096e1: / 改為 Landing page (public 入口); /browse 為 authenticated browse */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/browse" element={<HomePage />} />
      {/* /skills 是 listing alias — 使用者輸入網址或書籤回鏈時的直覺路徑 */}
      <Route path="/skills" element={<HomePage />} />
      <Route path="/skills/:id" element={<SkillDetailPage />} />
      {/* S098c: version diff page — frontend-only stub reuses /versions endpoint */}
      <Route path="/skills/:id/diff" element={<VersionDiffPage />} />
      {/* S096c: canonical route per ADR-003；既有 :id alias 並行不破 */}
      <Route path="/skills/:author/:name" element={<SkillDetailPage />} />
      <Route path="/publish" element={<PublishPage />} />
      {/* S098a: Step 2 中介驗證頁 — 4-step stepper + auto-poll + auto-navigate to /publish/review */}
      <Route path="/publish/validate" element={<PublishValidatePage />} />
      {/* S096d4a: post-upload result page (Step 3 Review) */}
      <Route path="/publish/review" element={<PublishReviewPage />} />
      {/* S098b: dedicated failure page — State A frontmatter error / State B high-risk submitted */}
      <Route path="/publish/failed" element={<PublishFailedPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      {/* S094a: 作者視角 dashboard — P6 SBE「作者查看自己的數據」 */}
      <Route path="/my-skills" element={<MySkillsPage />} />
      {/* S098e3-T04: reviewer queue — list OPEN flags + Resolve/Dismiss */}
      <Route path="/flags" element={<FlagsQueuePage />} />
      {/* S096g1: Request Board stub — read-only list；voting/claim 留 S096g2 */}
      <Route path="/requests" element={<RequestBoardPage />} />
      {/* S096f1: Collections stub — read-only list；install/create 留 S096f2 */}
      <Route path="/collections" element={<CollectionsPage />} />
      {/* S096h1: Notifications stub — read-only list + bell badge in AppShell */}
      <Route path="/notifications" element={<NotificationsPage />} />
      {/* S094b: 語意搜尋結果頁 — split inline → dedicated route */}
      <Route path="/search" element={<SearchResultsPage />} />
      {/* S094d: docs walkthrough — 第一篇 walkthrough */}
      <Route path="/docs/your-first-skill" element={<YourFirstSkillPage />} />
      {/* S098f: docs IA expansion — Overview + Risk tiers stubs */}
      <Route path="/docs/overview" element={<OverviewPage />} />
      <Route path="/docs/risk-tiers" element={<RiskTiersPage />} />
      {/* S098f2: 3 docs reference pages — SKILL.md spec / Frontmatter / Bundle */}
      <Route path="/docs/skill-md-spec" element={<SkillMdSpecPage />} />
      <Route path="/docs/frontmatter" element={<FrontmatterPage />} />
      <Route path="/docs/bundle" element={<BundleStructurePage />} />
      {/* S099e5: docs page LLM01-10 mapping (OWASP Top 10 alignment) */}
      <Route path="/docs/risk-scanner-scope" element={<RiskScannerScopePage />} />
      {/* S098f3: 5 final docs pages — 發佈 + API & Webhook 兩 group */}
      <Route path="/docs/upload-validate" element={<UploadValidatePage />} />
      <Route path="/docs/versioning" element={<VersioningPage />} />
      <Route path="/docs/semantic-search" element={<SemanticSearchPage />} />
      <Route path="/docs/rest-api" element={<RestApiPage />} />
      <Route path="/docs/event-payload" element={<EventPayloadPage />} />
      {/* S134: dev-only「我的認證」頁 — 後端 real-oauth profile 啟用時才有真資料；否則顯示提示 */}
      <Route path="/auth-debug" element={<AuthDebugPage />} />
      {/* unmatched URL 之前 render 空白 root，user 看不到 navbar 也沒 404 */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
