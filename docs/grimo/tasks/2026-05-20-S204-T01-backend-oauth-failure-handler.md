# S204-T01: Backend OAuth failure handler

## 對應規格
S204：OAuth Login Error Page

## 這個 task 要做什麼
`GET /login/oauth2/code/skillshub?...` 在 OAuth2 Login authentication 失敗時，後端要回 302 到 `/auth/error?reason=<safe-code>`，不要再回 `/login?error`。Cloud Run log 要有 `OAuth login failed` 和安全 key-value，讓人能查原因；redirect URL 和 log 不可洩漏 OAuth `code`、token、client secret 或完整 exception message。

## 使用者情境（BDD）
Given（前提）使用者從 Google 回到 `/login/oauth2/code/skillshub?state=abc&code=secret-code`
When（動作）Spring Security OAuth2 Login authentication 丟出 `AuthenticationException`
Then（結果）HTTP response 是 302，`Location` 是 `/auth/error?reason=<safe-code>`
And（而且）`Location` 不包含 `code`、token、client secret、exception message 或 `returnTo`
And（而且）backend log 含 `OAuth login failed`、`oauthErrorCode`、`exceptionClass`、`path`、`method`、`returnToPath`
And（而且）`returnToPath` 只保留 path，例如 `/publish?draftToken=abc` 在 log 中只能是 `/publish`

## 研究來源
- `docs/grimo/specs/2026-05-20-S204-auth-error-page.md` §2.4 / §4.2
- Spring Security `OAuth2LoginConfigurer` API：`oauth2Login()` 繼承 `failureHandler` / `failureUrl`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java`

## 先做 POC
- POC：not required — 官方 API 已確認 `failureHandler` 可掛在 OAuth2 Login chain；本 task 用 unit/slice tests 直接鎖行為。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java`
- 入口：Spring Security OAuth2 Login failure path。
- 必要行為：
  - 在 `AuthRedirectConfig` 新增 `AuthenticationFailureHandler oauthFailureHandler()` bean。
  - 把 OAuth provider `access_denied` map 成 `access_denied`。
  - 把 state/session mismatch 類型 map 成 `session_expired`。
  - 把 token endpoint/client/provider error map 成 `token_exchange_failed`。
  - 其他 exception map 成 `oauth_failed`。
  - failure handler 清掉 `SESSION_RETURN_TO`，redirect 到 `/auth/error?reason=...`，不把 `returnTo` 帶給前端。
  - `SecurityConfig.filterChain(...)` 注入 `ObjectProvider<AuthenticationFailureHandler>`，在 oauth login enabled branch 內呼叫 `login.failureHandler(failureHandler)`。
  - success handler 現有 redirect to stored `returnTo` 行為不可變。
- Log 欄位：
  - `oauthErrorCode`: safe reason
  - `exceptionClass`: exception simple class name
  - `path`: `request.getRequestURI()`
  - `method`: `request.getMethod()`
  - `returnToPath`: sanitized path only

## 單元測試 / 整合測試
- `AuthRedirectTest`
  - `@DisplayName("AC-S204-1: OAuth callback failure redirects to /auth/error safe reason")`
  - `@DisplayName("AC-S204-6: failure log uses safe fields and strips returnTo query")`
  - `@DisplayName("AC-S204-7: OAuth success still redirects to stored returnTo")`
- 若 log assertion 需要 capture appender，可沿用既有 log test helper；不要把 raw exception message 放進 redirect assertion。

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/AuthRedirectTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.security.AuthRedirectTest`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-20
Test: `AuthRedirectTest` (`backend/src/test/java/io/github/samzhu/skillshub/shared/security/AuthRedirectTest.java`)
Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java` (modified)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` (modified)
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/AuthRedirectTest.java` (modified)
Notes:
- RED: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.security.AuthRedirectTest` failed at `compileTestJava` because `AuthRedirectConfig.oauthFailureHandler()` did not exist.
- GREEN: same command passed with `BUILD SUCCESSFUL in 1m 56s`; JUnit XML reports `tests="18" skipped="0" failures="0" errors="0"`.
- Official docs checked: Spring Security `OAuth2LoginConfigurer` 7.0.5 inherits `failureHandler(...)`; `AuthenticationFailureHandler` owns the failed-authentication response.
