# S179-T01: Publish author auth-state display

## 對應規格
S179：Publish Author Anonymous Login Hint

## 這個 task 要做什麼
`/publish` 的作者欄位不能在未登入或登入狀態確認中顯示空白。這個 task 完成後，未登入使用者會看到「請先登入後發布」，登入狀態確認中會看到「正在確認登入狀態...」，已登入使用者維持顯示 `Alice Chen @alice`。未登入時按「發佈技能」仍只呼叫既有 `auth.login()`，不送 `/api/v1/skills/upload`。

## 使用者情境（BDD）
Given（前提）使用者開啟 `/publish`，`useAuth()` 回 `anonymous` 且 `useMe()` 沒有 current user data  
When（動作）頁面渲染作者欄位  
Then（結果）`data-testid="publish-author-display"` 顯示 `請先登入後發布`  
And（而且）未登入送出表單只呼叫 `useAuth().login()`，不呼叫 `/api/v1/skills/upload`

## 研究來源
- `docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/hooks/useAuth.ts`
- `frontend/src/pages/PublishPage.test.tsx`

## 先做 POC
- POC：not required — 只改既有 React page 的顯示狀態，沒有新套件、新 API、schema 或 framework SPI。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/PublishPage.tsx`
- 入口：`PublishPage`
- 必要行為：
  - `auth.status === 'loading'` 時，作者欄位 label 是 `正在確認登入狀態...`，不顯示 handle chip。
  - `auth.status === 'anonymous'` 時，作者欄位 label 是 `請先登入後發布`，不顯示 handle chip。
  - `auth.status === 'authenticated'` 且 `me` 有 display data 時，沿用 `getDisplayName(...)` 顯示姓名，並在 `me.handle` 有值時顯示 `@handle`。
  - `uploadSkill(...)` signature 和 `FormData` shape 不變，仍不送 `author`。

## 單元測試 / 整合測試
- `PublishPage.test.tsx`
  - `AC-S179-1: 未登入作者欄位顯示登入提示`
  - `AC-S179-2: 未登入送出只啟動登入流程`
  - `AC-S179-3: 登入中不顯空白作者`
  - `AC-S179-4: 已登入作者顯示維持 S154b 行為`

## 會改哪些檔案
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`
- `docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md`
- `docs/grimo/specs/spec-roadmap.md`

## 驗證方式
執行：`cd frontend && npm test -- PublishPage`

## 前置條件
- 無

## 狀態
PASS（2026-05-17）

## 實作結果
- Red：`cd frontend && npm test -- PublishPage` → FAIL，`AC-S179-1` 與 `AC-S179-3` 都收到空白作者欄位。
- Green：`frontend/src/pages/PublishPage.tsx` 新增 auth-state-aware `authorDisplay`，anonymous 顯示 `請先登入後發布`，loading 顯示 `正在確認登入狀態...`，authenticated 維持 `getDisplayName(...) + @handle`。
- Guard：未登入送出仍只呼叫 `auth.login()`，不呼叫 `/api/v1/skills/upload`；`FormData` 仍不含 `author`。

## 驗證結果
- `cd frontend && npm test -- PublishPage` → PASS（1 file / 17 tests）。
- `cd frontend && npm run verify` → PASS。
