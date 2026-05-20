# S204-T03: Route fallback and docs sync

## 對應規格
S204：OAuth Login Error Page

## 這個 task 要做什麼
把 `/auth/error` 的路由行為寫進測試和設計文件。使用者直接開 `/auth/error?reason=oauth_failed` 時，Spring 要 forward 到 React app；API typo `/api/auth/error` 仍然要 404，不可吃到 SPA shell。設計文件也要新增這個頁面，避免後續 UI inventory 漏掉。

## 使用者情境（BDD）
Given（前提）browser 直接請求 `/auth/error?reason=oauth_failed`
When（動作）Spring MVC 處理這個 extensionless path
Then（結果）response forward 到 `/index.html`
And（而且）React Router 可以 render `AuthErrorPage`
And（而且）`/api/auth/error` 回 404，不 forward 到 SPA shell

## 研究來源
- `docs/grimo/specs/2026-05-20-S204-auth-error-page.md` §3 AC-S204-8~9
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/SpaFallbackController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java`
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/debugging-playbook.md`

## 先做 POC
- POC：not required — `SpaFallbackController` 已是 catchall pattern；本 task 補針對 `/auth/error` 的 test 與 docs。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java`
  - `frontend/src/App.tsx`
  - `docs/grimo/ui/DESIGN.md`
  - `docs/grimo/debugging-playbook.md`
- 入口：Spring MVC GET `/auth/error` 與 React Router `/auth/error`。
- 必要行為：
  - `SpaFallbackControllerTest` 新增 `/auth/error?reason=oauth_failed` forward assertion。
  - `SpaFallbackControllerTest` 新增 `/api/auth/error` 404 assertion。
  - `docs/grimo/ui/DESIGN.md` Page Inventory 新增 `AuthErrorPage`，route `/auth/error`，auth false，beam `["Back to browse CTA"]`。
  - `docs/grimo/debugging-playbook.md` 補一段：`/auth/error?reason=token_exchange_failed` 代表 Google callback 到達 backend，但 OAuth2 authentication/token exchange 失敗。

## 單元測試 / 整合測試
- `SpaFallbackControllerTest`
  - `@DisplayName("AC-S204-9: /auth/error direct hit forwards to /index.html")`
  - `@DisplayName("AC-S204-9: /api/auth/error returns 404 and does not forward")`
- `App.test.tsx`
  - `@DisplayName("AC-S204-8: /auth/error route renders before wildcard NotFound route")`

## 會改哪些檔案
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java`
- `frontend/src/App.tsx`
- `frontend/src/App.test.tsx`
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/debugging-playbook.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.SpaFallbackControllerTest && cd ../frontend && npm test -- App.test.tsx`

## 前置條件
- S204-T02 PASS

## 狀態
pending（待做）
