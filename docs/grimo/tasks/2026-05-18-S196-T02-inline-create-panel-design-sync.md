# S196-T02: Inline request creation panel and design sync

## 對應規格
S196：Request Board 兩頁籤 UX

## 這個 task 要做什麼
把 `發起新需求` modal 改成 `我要開需求` 頁籤內的 inline form。使用者在同一個 `/requests` 頁面輸入 title 和 description，按 `送出需求` 後 frontend 發出既有 `POST /api/v1/requests`，成功後 invalidate request list，並回到 `瀏覽需求` 或顯示瀏覽入口。最後同步 DESIGN / prototype，讓文件不再描述 status tabs、claim 或 fulfill UI。

## 使用者情境（BDD）
Given（前提）使用者已登入，停在 `我要開需求` tab
When（動作）輸入 title `docker compose linter` 與 description `檢查 compose.yaml 結構`，按 `送出需求`
Then（結果）frontend 發出 `POST /api/v1/requests`，body 含 `{"title":"docker compose linter","description":"檢查 compose.yaml 結構"}`
And（而且）成功後 invalidate `["requests"]`，畫面回到 `瀏覽需求` tab 或顯示成功後的瀏覽入口

Given（前提）使用者未登入，停在 `我要開需求` tab
When（動作）按 `送出需求`
Then（結果）沿用 `AuthGatedButton` login flow，不直接送出 `POST /api/v1/requests`

Given（前提）S196 實作完成
When（動作）檢查 `docs/grimo/ui/prototype/Skills Hub Request Board.html` 與 `docs/grimo/ui/DESIGN.md`
Then（結果）prototype 仍呈現 `瀏覽需求` / `我要開需求` 兩頁籤方向
And（而且）DESIGN page inventory 的 `/requests` note 不描述 status-tab 或 claim/fulfill UI

## 研究來源
- `docs/grimo/specs/2026-05-18-S196-request-board-two-tab-ux.md`
- `frontend/src/components/CreateRequestModal.tsx`
- `frontend/src/api/skills.ts`
- `frontend/src/pages/RequestBoardPage.test.tsx`
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/ui/prototype/Skills Hub Request Board.html`

## 先做 POC
- POC：not required — `CreateRequestModal` 已經證明 `createRequest` mutation、`localizeApiError` 與 query invalidation 可用；本 task 只是把 dialog wrapper 拆成 inline panel，沒有新 library 或新 API。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/components/RequestCreatePanel.tsx`
- 入口：`RequestBoardPage` 的 `create` tab panel
- 必要行為：
  - 新增 `RequestCreatePanel`，把 `CreateRequestModal` 的 title / description form 邏輯移到 inline section。
  - 保留 `createRequest({ title, description })` payload，不新增欄位。
  - 保留 `localizeApiError` 錯誤顯示。
  - 保留 `AuthGatedButton`；未登入時不送 POST。
  - 成功後呼叫 `queryClient.invalidateQueries({ queryKey: ['requests'] })`，再呼叫 `onCreated()` 切回 browse。
  - 移除 `RequestBoardPage` 的 `showModal` state；若 `CreateRequestModal` 沒有其他 import，刪除或停止使用該檔。
  - 更新 DESIGN note，把 `/requests` 描述固定為 S196 two-tab / inline create direction。
  - 檢查 prototype 已符合方向；若 wording 與實作明顯不一致，做最小同步。
- Finding / response / DB 欄位：
  - POST body 只含 `title`、`description`。
  - invalidate query key 必須包含 `['requests']`，讓 `useRequests` 重新抓資料。

## 單元測試 / 整合測試
- `frontend/src/pages/RequestBoardPage.test.tsx`
  - `AC-S196-3: 我要開需求 tab inline submit 送出 title 與 description`
  - `AC-S196-3: 未登入按送出需求不送 POST 並走 login`
  - `AC-S196-7: /requests design/prototype wording 不描述 status tab 或 claim/fulfill`

## 會改哪些檔案
- `frontend/src/components/RequestCreatePanel.tsx`
- `frontend/src/components/CreateRequestModal.tsx`
- `frontend/src/pages/RequestBoardPage.tsx`
- `frontend/src/pages/RequestBoardPage.test.tsx`
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/ui/prototype/Skills Hub Request Board.html`

## 驗證方式
執行：`cd frontend && npm test -- RequestBoardPage && npm run verify`

## 前置條件
- S196-T01 PASS

## Status
PASS

## Result
Date: 2026-05-18
Test: `RequestBoardPage (S196)` (`frontend/src/pages/RequestBoardPage.test.tsx`)
Files changed:
- `frontend/src/components/RequestCreatePanel.tsx` (new)
- `frontend/src/components/CreateRequestModal.tsx` (deleted)
- `frontend/src/components/CreateCollectionModal.tsx` (modified)
- `frontend/src/components/PreferencesModal.tsx` (modified)
- `frontend/src/pages/RequestBoardPage.tsx` (modified)
- `frontend/src/pages/RequestBoardPage.test.tsx` (modified)
- `frontend/vite.config.ts` (modified)
- `docs/grimo/ui/DESIGN.md` (modified)
- `docs/grimo/ui/prototype/Skills Hub Request Board.html` (modified)
- `docs/grimo/tasks/2026-05-18-S196-T02-inline-create-panel-design-sync.md` (modified)
Notes: RED `cd frontend && npm test -- RequestBoardPage` failed on missing inline fields and design wording；GREEN `cd frontend && npm test -- RequestBoardPage` PASS（1 file / 8 tests）；`cd frontend && npm run verify` PASS。
