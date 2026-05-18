# S200-T02: Request detail header 顯示 requesterDisplayName

## 對應規格
S200：Request Requester Display Identity

## 這個 task 要做什麼
`/requests/:id` 詳情頁標題下方不能再 render `request.requesterId`。前端 type 要接收 `requesterDisplayName` / `requesterHandle`，header 用 `getDisplayName(...)` 取得可顯示的人名；缺 display data 時只顯示日期，不 fallback 顯示 `u_<id>`。

## 使用者情境（BDD）
Given（前提）frontend 收到 request detail：`requesterId="u_alice"`、`requesterDisplayName="Alice Chen"`、`requesterHandle="alice"`、`createdAt="2026-05-03T10:00:00Z"`
When（動作）`RequestDetailPage` render
Then（結果）header meta 顯示 `Alice Chen · 2026/5/3`
And（而且）整頁文字不包含 `u_alice`

Given（前提）frontend 收到 request detail：`requesterId="u_missing"`，但沒有 `requesterDisplayName/requesterHandle`
When（動作）`RequestDetailPage` render
Then（結果）header meta 只顯示日期
And（而且）整頁文字不包含 `u_missing`

Given（前提）comment row 有 `authorDisplayName`
When（動作）`RequestDetailPage` render
Then（結果）comment row 仍顯示 comment author display label
And（而且）comment delete 判斷仍用 `comment.authorId`

## 研究來源
- `docs/grimo/specs/2026-05-18-S200-request-requester-display-identity.md`
- `frontend/src/api/skills.ts`
- `frontend/src/pages/RequestDetailPage.tsx`
- `frontend/src/lib/displayName.ts`
- `frontend/src/components/CommentList.tsx`
- `frontend/src/pages/RequestDetailPage.test.tsx`
- S192 shipped：human labels never fall back to platform user id

## 先做 POC
- POC：not required — 沿用既有 `getDisplayName(...)` helper 與 page tests。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/api/skills.ts`、`frontend/src/pages/RequestDetailPage.tsx`
- 入口：`SkillRequest` / `RequestDetail` type、`RequestDetailPage` header render
- 必要行為：
  - `SkillRequest` 新增 `requesterDisplayName?: string | null`、`requesterHandle?: string | null`。
  - `RequestDetailPage` header 用：
    `getDisplayName({ author: request.requesterId, authorDisplayName: request.requesterDisplayName, authorHandle: request.requesterHandle })`
  - display label 有值時顯示 `<label> · <date>`；沒值時只顯示 `<date>`。
  - 不把 `requesterId` 傳給 `getAuthorRouteSegment` 或任何 visible label fallback。
  - 測試整頁不含 raw id 時，使用 `document.body.textContent` 或 regex，避免 `queryByText('u_alice')` 漏掉合併文字。

## 單元測試 / 整合測試
- `RequestDetailPage.test.tsx`
  - `AC-S200-3: detail header 顯示 requesterDisplayName，不顯 requesterId`
  - `AC-S200-5: display data 缺失時 UI 不 fallback 顯示 u_<id>`
  - 更新既有 `AC-S192-6` comment row test，確保 header raw id 不污染整頁 assertion

## 會改哪些檔案
- `frontend/src/api/skills.ts`
- `frontend/src/pages/RequestDetailPage.tsx`
- `frontend/src/pages/RequestDetailPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- RequestDetailPage.test.tsx`

## 前置條件
- S200-T01 PASS

## Status
PASS

## Result
Date: 2026-05-19
Test: `AC-S200-3: detail header 顯示 requesterDisplayName，不顯 requesterId`、`AC-S200-5: display data 缺失時 UI 不 fallback 顯示 u_<id>` (`frontend/src/pages/RequestDetailPage.test.tsx`)
Files changed:
- `frontend/src/api/skills.ts` (modified)
- `frontend/src/pages/RequestDetailPage.tsx` (modified)
- `frontend/src/pages/RequestDetailPage.test.tsx` (modified)
Notes:
- RED: `cd frontend && npm test -- RequestDetailPage.test.tsx` failed because page text still contained `u_alice` and missing display data rendered `u_missing · 2026/5/3`.
- GREEN: same command PASS — `9 tests passed`，`Test Files 1 passed`。
- `RequestDetailPage` header now renders `requesterDisplayName` / `requesterHandle` through `getDisplayName(...)`; when both are missing, it renders only the date.
