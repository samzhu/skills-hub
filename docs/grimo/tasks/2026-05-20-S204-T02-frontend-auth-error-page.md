# S204-T02: Frontend auth error page

## 對應規格
S204：OAuth Login Error Page

## 這個 task 要做什麼
新增 React route `/auth/error`。使用者直訪或被後端導到這頁時，要看到 Skills Hub 的繁中錯誤頁、唯一互動按鈕「返回瀏覽」，並且看不到 Spring default 英文 `Invalid credentials`、導覽列連結、右上角登入按鈕或任何 raw query value。

## 使用者情境（BDD）
Given（前提）使用者開啟 `/auth/error?reason=token_exchange_failed`
When（動作）React Router render `AuthErrorPage`
Then（結果）頁面顯示 `登入沒有完成`
And（而且）文字包含 `Google 已回到 Skills Hub，但後端沒有完成認證`
And（而且）只顯示一個主要操作「返回瀏覽」，連到 `/browse`
And（而且）頁首只有 `Skills Hub` 品牌，不顯示導覽連結、通知鈴鐺或登入按鈕
And（而且）頁面只顯示 safe enum error code，不顯示 `Invalid credentials`、OAuth `code` 或 raw unknown query

## 研究來源
- `docs/grimo/specs/2026-05-20-S204-auth-error-page.md` §3 AC-S204-2~5 / §4.3
- `docs/grimo/ui/DESIGN.md` Page Inventory + EmptyState rules
- `frontend/src/components/EmptyState.tsx`
- `frontend/src/components/AppShell.tsx`
- `frontend/src/App.tsx`

## 先做 POC
- POC：not required — 使用現有 React Router / AppShell / EmptyState pattern，無新套件或未知 browser API。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/pages/AuthErrorPage.tsx`
  - `frontend/src/pages/AuthErrorPage.test.tsx`
  - `frontend/src/App.tsx`
  - `frontend/src/components/AppShell.tsx`
  - `frontend/src/components/AppShell.test.tsx`
  - `frontend/src/App.test.tsx`
- 入口：`<Route path="/auth/error" element={<AuthErrorPage />} />`
- 必要行為：
  - `AuthErrorPage` 讀 `reason` query，只接受 `session_expired`、`access_denied`、`token_exchange_failed`、`oauth_failed`；其他值 normalize 成 `oauth_failed`。
  - `token_exchange_failed` 顯示 Cloud Run revision 提示。
  - `access_denied` 顯示 Google 沒有授權本次登入，不顯示 Cloud Run revision 提示。
  - `session_expired` 顯示登入流程已失效，請回到瀏覽頁重新開始。
  - 沒有 `reason` 時顯示 generic `登入沒有完成`。
  - 使用 `EmptyState tone="redirect"`，primary action label 是「返回瀏覽」，href 是 `/browse`。
  - `AppShell` 加 minimal header option，讓 `/auth/error` 只顯示品牌，不顯示 nav/bell/auth area/mobile menu。
  - `AuthErrorPage` 不呼叫 `useAuth.login()`，也不 render 登入 CTA。

## 單元測試 / 整合測試
- `AuthErrorPage.test.tsx`
  - `@DisplayName("AC-S204-2: /auth/error without reason renders generic recovery page")`
  - `@DisplayName("AC-S204-3: token_exchange_failed shows backend authentication copy and Cloud Run hint")`
  - `@DisplayName("AC-S204-4: access_denied shows consent copy without Cloud Run hint")`
  - `@DisplayName("AC-S204-5: session_expired primary action links to /browse")`
  - `@DisplayName("AC-S204-2: unknown reason is normalized and raw query is not rendered")`
- `AppShell.test.tsx`
  - `@DisplayName("AC-S204-2: minimal auth error header hides nav, bell, and auth area")`
- `App.test.tsx`
  - `@DisplayName("AC-S204-8: /auth/error route renders before wildcard NotFound route")`

## 會改哪些檔案
- `frontend/src/pages/AuthErrorPage.tsx`
- `frontend/src/pages/AuthErrorPage.test.tsx`
- `frontend/src/App.tsx`
- `frontend/src/App.test.tsx`
- `frontend/src/components/AppShell.tsx`
- `frontend/src/components/AppShell.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- AuthErrorPage.test.tsx AppShell.test.tsx App.test.tsx`

## 前置條件
- S204-T01 PASS

## 狀態
pending（待做）
