# S196-T01: Request Board browse tabs and ranking

## 對應規格
S196：Request Board 兩頁籤 UX

## 這個 task 要做什麼
把 `/requests` 的主畫面改成兩個頁籤：`瀏覽需求` 和 `我要開需求`。這個 task 只處理 `瀏覽需求` 頁籤：預設載入票數排序、顯示需求卡、保留投票按鈕和詳情連結、右側用同一份資料顯示需求排行，並讓空狀態可以切到 `我要開需求`，不離開 `/requests`。

## 使用者情境（BDD）
Given（前提）`GET /api/v1/requests?sort=votes` 回傳三筆需求，票數分別為 38、24、19
When（動作）使用者打開 `/requests`
Then（結果）畫面顯示 `瀏覽需求` 和 `我要開需求` 兩個 tab，且 `瀏覽需求` 的 tab `aria-selected="true"`
And（而且）畫面沒有 `尚無勇者`、`接手中`、`已結案` 這三個主頁籤
And（而且）需求卡顯示 title、description、vote count、`我也要` 投票按鈕與 `/requests/:id` detail link
And（而且）`需求排行榜` 依 38、24、19 的票數順序顯示同三筆需求

Given（前提）使用者在 `瀏覽需求` tab
When（動作）點 `最新`
Then（結果）frontend 呼叫 `/api/v1/requests?sort=created`
And（而且）任何 request URL 都不包含 `status=`

Given（前提）`GET /api/v1/requests?sort=votes` 回傳空陣列
When（動作）使用者停在 `瀏覽需求` tab
Then（結果）畫面顯示「目前還沒人發起需求」
And（而且）主要 action 是切到 `我要開需求` tab，不是跳到 `/browse`

## 研究來源
- `docs/grimo/specs/2026-05-18-S196-request-board-two-tab-ux.md`
- `frontend/src/pages/RequestBoardPage.tsx`
- `frontend/src/hooks/useRequests.ts`
- `frontend/src/components/VoteButton.tsx`
- WAI-ARIA APG Tabs Pattern: `tablist` / `tab` / `tabpanel`、`aria-selected`、`aria-controls`、`aria-labelledby`

## 先做 POC
- POC：not required — S196 只重排現有 React page，沿用已存在的 `useRequests({ sort })`、`VoteButton`、React state 與 Testing Library；沒有新 SDK、framework SPI 或外部 API 假設。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/RequestBoardPage.tsx`
- 入口：`RequestBoardPage`
- 必要行為：
  - 新增 `activeTab: 'browse' | 'create'`，預設 `browse`。
  - 新增 `sort: 'votes' | 'created'`，預設 `votes`，並呼叫 `useRequests({ sort })`。
  - 建立 `role="tablist"`，兩個 `role="tab"` 對應兩個 `role="tabpanel"`；active tab 設 `aria-selected="true"`，inactive tab 設 `false`。
  - `瀏覽需求` panel 顯示需求卡，不再用舊的 single-row list 當唯一 layout；每張卡保留 `VoteButton` 和 `<Link to="/requests/${id}">`。
  - 排行榜從同一份 `requests` 取前幾筆，不額外打 API。
  - 排序控制只用 `votes` / `created`，不得送 `status=`。
  - 空狀態 action 呼叫 `setActiveTab('create')`，不導向 `/browse`。
- Finding / response / DB 欄位：
  - `sort`: 只能是 `votes` 或 `created`。
  - `request.id`: detail link path `/requests/:id`。
  - `request.voteCount`: 需求卡與排行榜排序來源。

## 單元測試 / 整合測試
- `frontend/src/pages/RequestBoardPage.test.tsx`
  - `AC-S196-1: /requests 顯示瀏覽需求與我要開需求 tabs`
  - `AC-S196-2: 瀏覽需求顯示需求卡、投票按鈕、detail link 與排行`
  - `AC-S196-4: 排序只呼叫 sort=votes 或 sort=created 且不送 status`
  - `AC-S196-5: 空狀態 action 切到我要開需求 tab`
  - `AC-S196-6: tabs 使用 tablist/tab/tabpanel 與 aria-selected`

## 會改哪些檔案
- `frontend/src/pages/RequestBoardPage.tsx`
- `frontend/src/pages/RequestBoardPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- RequestBoardPage`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-18
Test: `RequestBoardPage (S196-T01)` (`frontend/src/pages/RequestBoardPage.test.tsx`)
Files changed:
- `frontend/src/pages/RequestBoardPage.tsx` (modified)
- `frontend/src/pages/RequestBoardPage.test.tsx` (modified)
- `docs/grimo/tasks/2026-05-18-S196-T01-request-board-browse-tabs.md` (modified)
- `docs/grimo/specs/spec-roadmap.md` (modified)
Notes: `cd frontend && npm test -- RequestBoardPage` PASS（1 file / 5 tests）；`cd frontend && npm run verify` PASS。
