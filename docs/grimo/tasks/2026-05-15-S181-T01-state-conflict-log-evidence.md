# S181-T01: STATE_CONFLICT log evidence

## 對應規格
S181：Authenticated State Conflict Observability

## 這個 task 要做什麼
`GlobalExceptionHandler` 處理 `IllegalStateException` 時，HTTP 409 response body 繼續維持 `STATE_CONFLICT`，但 WARN log 要多出 request path、method、exception class、message、root cause class/message。這樣下次正式站 Chrome 重測 `/publish/validate` 時，Cloud Run application log 可以直接看出是 `/api/v1/me` 或 `/api/v1/skills/{id}` 哪一條路徑丟了哪個 exception。

## 使用者情境（BDD）
Given（前提）`GET /api/v1/me` 的 controller/application path 丟出 `IllegalStateException("Failed to generate unique user_id after 5 retries")`

When（動作）Spring MVC 交給 `GlobalExceptionHandler.handleStateConflict` 處理

Then（結果）API response 仍是 HTTP 409，body `error` 仍是 `STATE_CONFLICT`

And（而且）WARN log 包含 `path=/api/v1/me`、`method=GET`、`exceptionClass=java.lang.IllegalStateException`、exception message、root cause class/message

And（而且）WARN log 不包含 `Authorization` header、cookie、email、OAuth subject 這類 credential / identity 欄位

## 研究來源
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`
- Spring MVC `@ExceptionHandler` official docs：handler method 可接 `ServletRequest` / `WebRequest` arguments
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java`

## 先做 POC
- POC：not required — S181 spec 已確認 Spring MVC `@ExceptionHandler` 支援 request argument；本 task 是 handler method + POJO test 的小改動。

## 正式程式怎麼做
- Class / file 名稱：`GlobalExceptionHandler.java`
- 入口：`handleStateConflict(IllegalStateException ex, HttpServletRequest request)`
- 必要行為：
  - response status 保持 HTTP 409
  - response body 保持 `ErrorResponse("STATE_CONFLICT", ex.getMessage(), timestamp)`
  - WARN log 增加 `path`、`method`、`exceptionClass`、`message`、`rootCauseClass`、`rootCauseMessage`
  - 不讀、不印 request headers、cookies、Authorization、session id、email、display name、OAuth subject

## 單元測試 / 整合測試
- `GlobalExceptionHandlerTest`
  - `@DisplayName("AC-S181-1/2/3/5: IllegalStateException 409 logs request metadata and preserves response body")`
  - 直接用 `MockHttpServletRequest("GET", "/api/v1/me")` 呼叫 handler，並用 `OutputCaptureExtension` 驗證 log 內容

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java`
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`
- `docs/grimo/tasks/2026-05-15-S181-T01-state-conflict-log-evidence.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-16
Test: `stateConflictLogsRequestMetadataAndPreservesResponse` (`backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java`)
Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` (modified)
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java` (modified)
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md` (modified)
- `docs/grimo/tasks/2026-05-15-S181-T01-state-conflict-log-evidence.md` (new)
Notes:
- Red: `./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest` failed at `compileTestJava` because `handleStateConflict` still accepted only `IllegalStateException`.
- Green: same command passed with `BUILD SUCCESSFUL in 2m 20s`.
- Production Chrome / Cloud Run evidence is intentionally left for S181-T02 after deploy.
