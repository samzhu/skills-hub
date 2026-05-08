# S156: List Clickability + Analytics Hero Card 修正

> Spec: S156 | Size: S(5) | Status: 🚧 in-progress（2/3 — #1 早已 ship S100e + #3 ship 2026-05-08；#2 RequestDetailPage 拆 S156b 因新 page 偏 M）
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 三個獨立但同類的「列表項看似可點但不可點」+ analytics「前 3 名」placeholder 問題。

---

## 1. Goal

讓平台的列表項目「視覺暗示可點 → 真的可點」一致：

1. **熱門技能 leaderboard**（`/analytics`）：skill row 點擊 → `/skills/{id}`
2. **需求看板 row**（`/requests`）：點擊 → `/requests/{id}` request detail 頁
3. **Analytics「熱門排行」hero metric card**：移除（顯「前 3 名」純字面文字無實質 metric）

**為什麼三件一隻 spec：** 都是 LandingPage / list pages 的「探索路徑」破口。平台核心價值是「找到對的 skill」，列表斷掉 → 探索受阻。

**非目標：**
- 不重做 leaderboard 視覺
- 不加 analytics 時間 filter / chart（留 follow-up）
- 不加 request 留言 / 認領流程 UI（已 ship S096g2，本 spec 只加 detail page 殼 + 串既有 actions）

---

## 2. Approach

### 2.1 #1 Analytics Leaderboard 點擊跳轉

**現況**：`AnalyticsPage` 的「熱門技能 前 10 名」row 是純文字 + 進度條：
```tsx
<div className="row">
  <span>{rank}</span>
  <span>{skill.name}</span>          {/* 純 span，無 Link */}
  <ProgressBar value={skill.downloadCount} />
  <span>{skill.downloadCount}</span>
</div>
```

**修正**：包 `<Link>`：
```tsx
<Link to={`/skills/${skill.id}`} className="row hover:bg-muted/30">
  ...
</Link>
```

注意 leaderboard `skill.id` 是否在 API response：若 backend 只回 `name + count`，需擴 response 含 id。檢查 `/api/v1/analytics/top-skills` schema → 若無 id 就動 backend；有就純前端。

### 2.2 #2 Request Detail Page

**現況**：`/requests/:id` 路由不存在；row 在 `RequestBoardPage` 是純 `<div>` + `hover:bg-muted/3` 視覺暗示但沒有 click target。Detail data（描述、留言、claim 對象）只在 hover 不顯示，需 click 進去看。

**設計（最小 detail page）**：

```
/requests/:id  → RequestDetailPage
├── ← 返回需求看板
├── Header
│   ├── 標題 + StatusPill
│   ├── 發起人（顯 displayName per S154 — 在 S154 ship 後自動跟上；S156 先用既有 displayName helper）
│   └── 票數（VoteButton）
├── 描述（full text，不像 row 截斷）
├── Action bar（依現有 RequestActionBar 邏輯）
│   ├── OPEN + isRequester → 刪除
│   ├── OPEN + !isRequester → 認領
│   ├── IN_PROGRESS + isClaimer → 釋放 / 上架完成
│   └── FULFILLED → 查看技能
└── 留言區（V0：純顯既有 description；留言串留 follow-up spec）
```

`RequestRow` title 改 `<Link to={\`/requests/${request.id}\`}>`。

API：既有 `GET /api/v1/requests/{id}` 已 expose（per `useRequest(id)` hook 推測，需確認；若無則 add）。

### 2.3 #3 Analytics「熱門排行 / 前 3 名」 metric card

**現況**：4 個 metric card grid，第 4 張顯：
```
熱門排行
前 3 名
```
不是數字也不是 metric，是「leaderboard 第幾名以前」的提示文字 — 沒實質意義，第 1 / 4 張並列看視覺很怪。

**選項**：
- A. 直接移除這張 card（grid 變 3 張）
- B. 換成有意義的 metric — 例如「平均下載 / 技能」`avg = totalDownloads / totalSkills`
- C. 換成「最熱門技能名稱」（與下方 leaderboard 第 1 重複，不選）

**選 A**：grid 3 張對齊 hero 4 列既有 metric strip pattern；最簡單；資訊密度不變（leaderboard 已在下方）。

### 2.4 三件 fix 互相關係

- `#1`、`#2` 都增加 list 的 click target — 同類修正
- `#3` 是純移除 — 互不相干但同 page (analytics)
- 三項共享一個「audit fix bundle」context；做完後 hero/leaderboard 探索路徑全通

---

## 3. Acceptance Criteria

```
AC-1: Analytics leaderboard skill row 可點擊跳轉
  Given 使用者訪問 /analytics 看到熱門技能 list
  When 點任一 row（不論點 name、progress bar、count）
  Then 導航至對應 /skills/{id}
  And SkillDetailPage 載入

AC-2: Request board row 可點擊進 detail page
  Given 使用者訪問 /requests 看到需求 row list
  When 點任一 row 標題
  Then 導航至 /requests/{id}
  And RequestDetailPage 載入

AC-3: Request detail page 顯示完整描述 + actions
  Given /requests/{existing-id} 載入
  When 頁面 render
  Then 顯示完整 description（不截斷）+ vote button + status pill +
       action bar（依 status & 當前 user role）+ 「← 返回需求看板」link

AC-4: Request detail page 不存在 ID → 友善 404
  Given /requests/{nonexistent-id}
  When 頁面 render
  Then 顯示「找不到此需求」EmptyState + 「返回需求看板」link
  And 不顯「載入錯誤 / 請稍後重試」(per S153 同精神)

AC-5: Analytics「熱門排行 前 3 名」card 移除
  Given 使用者訪問 /analytics
  When 頁面 render
  Then hero metric grid 只 3 張 card（總技能數 / 總下載次數 / 本週新增）
  And 不出現「熱門排行 前 3 名」字眼

AC-6: 既有 actions 行為不變
  Given Request action（vote / 認領 / 刪除 / 釋放 / 上架完成）
  When 在 detail page 觸發
  Then 行為與 board page 上等同（共用 mutation hooks）
```

驗證指令：`cd frontend && npm test`（per qa-strategy.md）+ deploy 後手動測

---

## 4a. Verification（已 ship 部分）

| 項目 | 結果 |
|------|------|
| #1 Analytics leaderboard 點擊跳轉 | ✅ already shipped — S100e（v4.x）已加 Link wrap + author guard；spec 假設過時 |
| #3 「熱門排行」hero metric card 移除 | ✅ shipped 2026-05-08 — AnalyticsPage grid 4-up → 3-up；移除冗餘 card |
| `npx vitest run AnalyticsPage.test.tsx` | ✅ 5/5 PASS（4 既有 S100e link guard test + 1 新 S156 #3 移除 assertion） |
| #2 RequestDetailPage（新 page）| ⏳ 拆 S156b — 新 page + hook + route + backend SPA fallback (after S152)；不在 1-tick 範圍 |

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/pages/AnalyticsPage.tsx` | leaderboard row 包 `<Link>`；移除「熱門排行」card；grid 改 3 列 |
| `frontend/src/pages/RequestBoardPage.tsx` | `RequestRow` 標題改 `<Link to={\`/requests/${id}\`}>` |
| `frontend/src/pages/RequestDetailPage.tsx` | 新增 — 仿 S150 `CollectionDetailPage` pattern |
| `frontend/src/hooks/useRequest.ts` | 新增 — `GET /api/v1/requests/:id` |
| `frontend/src/App.tsx` | 加 `<Route path="/requests/:id" element={<RequestDetailPage />} />` |
| `backend/src/main/java/.../shared/api/SpaFallbackController.java` | 加 `/requests/**` 進 allowlist（**或** S152 ship 後自動 cover） |
| **Tests** | 對應 6 個 AC |

注意：本 spec 與 **S152**（SPA fallback 移除 allowlist）有交集 — 若 S152 先 ship，`SpaFallbackController` 改動可省。建議 S152 先做。

---

## 5. Test Plan

### 5.1 自動化（vitest + MemoryRouter）

```ts
// AnalyticsPage.test.tsx
it('AC-1: leaderboard row links to skill detail', () => {
  // mock fetch /api/v1/analytics/top-skills with skill ids
  renderPage()
  await waitFor(() => screen.getByText('deep-research'))
  const link = screen.getByText('deep-research').closest('a')
  expect(link).toHaveAttribute('href', '/skills/<id>')
})

it('AC-5: 熱門排行 card 不再顯示', () => {
  renderPage()
  expect(screen.queryByText('熱門排行')).toBeNull()
  expect(screen.queryByText('前 3 名')).toBeNull()
})

// RequestDetailPage.test.tsx
it('AC-3: shows full description + back link', () => {
  // mock fetch /api/v1/requests/c1
  renderPage('c1')
  await waitFor(() => screen.getByText(/full description here/))
  expect(screen.getByText('需求看板').closest('a')).toHaveAttribute('href', '/requests')
})

it('AC-4: 404 → 找不到此需求', async () => {
  // mock fetch returns 404
  renderPage('nonexistent')
  await waitFor(() => screen.getByText('找不到此需求'))
})
```

### 5.2 手動 LAB

deploy 後：
- [ ] `/analytics` 點 leaderboard row → /skills/{id}
- [ ] `/requests` 點 row → /requests/{id}
- [ ] `/requests/{id}` 顯完整描述 + vote + actions
- [ ] `/requests/nonexistent` → 找不到 EmptyState
- [ ] `/analytics` hero 只 3 張 card

---

## 6. 後續 follow-up

- **S157（potential）**: Request 留言串 / 討論 thread — 本 spec 只做 detail 殼
- **Analytics 強化**：時間 filter（7d/30d/90d）、download trend chart per skill — 留另開 spec（與 S145 訂閱管理一併考量）

## 7. 補充 — Vote behavior 兩個小議題（並入 S156 範圍）

LAB audit 後續發現兩個與 vote 相關 issue，並入本 spec 處理（detail page 順手解）：

### 7.1 GET request response 缺 `voted` 欄位

**現況**：`GET /api/v1/requests` / `GET /api/v1/requests/{id}` response 只有 `voteCount`，沒有 `voted: boolean` 表示「當前 viewer 是否已投票」。Frontend `VoteButton` 因此無法在初始 render 時正確顯示 voted 狀態（hot reload / 直訪頁無 voted hint）。

**修正**：
- API 加 viewer-aware 欄位 `voted: boolean`（背後查 `request_votes` 表 `EXISTS` user-request pair）
- frontend `VoteButton` 改用 `request.voted` 為 initial state

### 7.2 Self-vote 沒擋

**現況**：使用者可以對自己發起的需求投票（`requesterId === currentSub` 仍 200）。對 leaderboard / vote-driven priority 是 anti-pattern：發起人自投 1 票，inflate own request rank。

**修正**：
- backend `RequestVoteService.toggle()` 檢查 `request.requesterId == currentUser.sub` → throw `SelfVoteNotAllowedException` → 400
- frontend `VoteButton` 對 self request disable（hover tooltip「不能對自己的需求投票」）

### 7.3 補 AC

```
AC-7: GET request response 含 voted 欄位
  Given 使用者已對 request X 投票
  When GET /api/v1/requests/{X}
  Then response 含 voted: true
  And /api/v1/requests list response 每筆也含 voted

AC-8: Self-vote 被擋
  Given Alice 是 request X 的 requester
  When Alice POST /api/v1/requests/{X}/vote
  Then backend 回 400 SELF_VOTE_NOT_ALLOWED
  And request voteCount 不變

AC-9: Frontend self-request 的 VoteButton disabled
  Given Alice 看自己發起的 request
  When VoteButton render
  Then button disabled + tooltip「不能對自己的需求投票」
```
