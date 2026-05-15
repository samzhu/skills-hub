# S181 — Authenticated State Conflict Observability

> SpecID: S181
> Status: 📐 in-design
> Date: 2026-05-15
> Size: XS(6)
> Related: S180 Chrome logged-in validate blocker, S162/S162b API error shape, S141 `/api/v1/me`, S154 user_id upsert, S169 permission contract

---

## 1. Goal

`/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5` 在 Chrome 登入態仍顯示「無法載入 skill」，而 Cloud Run 只看得到 `/api/v1/me` 與 `/api/v1/skills/{id}` HTTP 409；S181 要先讓這類 409 在 response 與 Cloud Run log 裡帶出可追的 root exception、request path、method、trace context，避免下一輪只能猜。

實際 production evidence：

| 來源 | 查到什麼 |
| --- | --- |
| Chrome validate page | 頁面仍顯示 `登入` 與 `無法載入 skill (id=028cecf1-3326-4327-bbe9-28b4e6fab6d5)`。 |
| Chrome console（上一輪點登入後） | `[QueryCache] ... fetchMe failed: HTTP 409`。 |
| Cloud Run request log | `2026-05-15T17:35:34.223578Z GET /api/v1/me 409 trace=22da8014fd6eb2727e2b64169a440467`。 |
| Cloud Run request log | `2026-05-15T17:35:34.224361Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5 409 trace=0bf6cdcd5eb7a08b7e2b64169a440d1a`。 |
| Anonymous curl | `curl /api/v1/me` 回 HTTP 401，不是 409；表示 409 是 Chrome session / authenticated path 才會進入。 |
| Cloud Run app log query | 查 `State conflict` / `STATE_CONFLICT` 無結果；目前 request log 有 409，但看不到丟出 `IllegalStateException` 的 class/message/path。 |

S181 不直接猜 root fix。依 AGENTS.md 的 Log-Driven Debugging，先補可觀測性，再用正式站重測得到的 exception message 決定下一個修法 task。

相依狀態：

| Spec | 狀態 | 是否阻擋 S181 |
| --- | --- | --- |
| S180 | ⏳ deployed | S181 是 S180 AC-S180-4 的 follow-up blocker；不改 S180 code。 |
| S162b | ⏸ deferred | 401/403 JSON shape 是較大的 API consistency spec；S181 只處理 409 debug evidence，不吸收 S162b。 |
| S179 | 📐 in-design | 前端 publish author 文案，不碰 409 backend path。 |
| S178 | 📐 in-design | Browse/search route cleanup，不碰 `/api/v1/me` 或 state conflict。 |

Spec overlap scan：Active specs 中只有 S162b 也碰 error response，但 S162b scope 是 SecurityFilterChain 401/403；S181 scope 是 controller/application path 409 `IllegalStateException` 可觀測性，重疊低於 50%，不 supersede。

Roadmap note：`docs/grimo/specs/spec-roadmap.md` 目前已有未提交的 S179/S180 local diff；本 tick 不改 roadmap，避免把 unrelated user changes 混入 S181 commit。下一次 roadmap 可由 loop controller 合併 S181 row。

## 2. Research And Design

### 2.1 Current code facts

| File | 查到什麼 | 對設計的影響 |
| --- | --- | --- |
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | `handleStateConflict(IllegalStateException)` 回 HTTP 409 + `ErrorResponse("STATE_CONFLICT", ex.getMessage(), ...)`；WARN log 只有 `errorCode` 與 `message`。 | response body 理論上有 root message，但 Chrome tooling 本輪讀不到 network body；log 也沒有 path/method/exception class/trace hint。 |
| `backend/src/main/java/.../shared/security/MeController.java` | `GET /api/v1/me` 先呼叫 `CurrentUserProvider.current()`，OAuth path 會 lazy upsert `users` row。 | `/api/v1/me` 409 可能來自 authenticated user lookup/upsert，不是 anonymous 401 path。 |
| `backend/src/main/java/.../shared/security/CurrentUserProvider.java` | `JwtAuthenticationToken` / `OAuth2AuthenticationToken` 都會呼叫 `UserUpsertService.upsertFromOidc(...)`；LAB/anonymous fallback 不查 users 表。 | Chrome session 與 anonymous curl 行為不同，符合 authenticated-only 409。 |
| `backend/src/main/java/.../shared/security/UserUpsertService.java` | `generateUniqueUserId()` 在 5 次撞 id 後 throw `IllegalStateException`；handle slug 也有 collision/fallback path。 | 這是 `/api/v1/me` 409 的候選根因之一，但現在沒有 production exception message，不可直接定案。 |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | detail/read path 也有數個 `IllegalStateException`（例如 JSONB parse failure）。 | `/api/v1/skills/{id}` 409 可能是另一個 root cause；S181 log 必須能分辨 path + exception message。 |
| `frontend/src/api/auth.ts` | `fetchMe()` 只把 401 轉 `null`；其他非 2xx 直接 throw `fetchMe failed: HTTP <status>`。 | 前端 console 不會顯示 backend error body；debug 必須靠 network body 或 server log。 |

### 2.2 Official references

| Source | Summary | Decision |
| --- | --- | --- |
| [Spring Framework MVC `@ExceptionHandler`](https://docs.enterprise.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html) | MVC `@ExceptionHandler` 可接 `ServletRequest` / `WebRequest` 等 method arguments，也可回 `ResponseEntity`。 | S181 可以在 `handleStateConflict` 加 `HttpServletRequest` 參數，記錄 path/method，不需要自訂 filter。 |
| [Spring Security OAuth2 Resource Server](https://docs.enterprise.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html) | Bearer token 驗證成功後，`Authentication` 會被放到 `SecurityContextHolder`，再進入 application logic。 | `/api/v1/me` 409 不是 unauthenticated entry point；它已進 controller/application path，應由 `GlobalExceptionHandler` 觀測。 |
| S141 archived spec | `/api/v1/me` response 應回 current user display claims；OAuth/LAB shape 要穩定。 | S181 不改 `/api/v1/me` 成功 shape，只固定失敗時 debug evidence。 |
| S154 archived spec / current code | OAuth user 會 lazy upsert 成 platform `user_id`。 | user upsert 是候選根因，下一輪 task 要用 production message 驗證。 |

### 2.3 Flow diagram

```text
Chrome validate page
  → GET /api/v1/me
      → SecurityContext 已有 OAuth session/token
      → MeController.me()
      → CurrentUserProvider.current()
      → UserUpsertService.upsertFromOidc(...)
      → IllegalStateException?  → GlobalExceptionHandler 409

  → GET /api/v1/skills/028cecf1-...
      → SkillQueryService detail path
      → permission / projection / JSONB parse / aggregate read
      → IllegalStateException?  → GlobalExceptionHandler 409

目前 Cloud Run 只留下 request log: path + status=409
S181 要補 app log: path + method + exceptionClass + message + rootCauseClass + rootCauseMessage
```

### 2.4 Approach comparison

| Approach | 改哪裡 | 跑出實際行為 | 成本 |
| --- | --- | --- | --- |
| A. 直接猜 `/api/v1/me` 是 `UserUpsertService` collision 然後修 | `UserUpsertService` | 如果猜對，`/api/v1/me` 會從 409 變 200；如果猜錯，Chrome validate 仍卡住。 | 快，但可能又是同錯誤不變。 |
| B. `GlobalExceptionHandler` 補 409 request/path/root-cause log + 測試（recommended） | `GlobalExceptionHandler.handleStateConflict` + test | 下次 Chrome 重測時，Cloud Run 會出現 `STATE_CONFLICT path=/api/v1/me exceptionClass=... message=...`；可用 trace/time 串回 UI 操作。 | XS；符合 Log-Driven Debugging。 |
| C. 前端 `fetchMe()` 把 409 body 印到 console | `frontend/src/api/auth.ts` | Chrome console 可能顯示 backend message，但其他 409 path 仍缺 server-side trace；也會讓 production console 暴露 server message。 | 小，但觀測點不完整。 |

Chosen approach: B。

### 2.5 Design decision

`handleStateConflict` 目前已回 `STATE_CONFLICT` body；S181 只補 server-side evidence，不改 HTTP status，也不把所有 `IllegalStateException` 重新分類。

Implementation target:

```java
@ExceptionHandler(IllegalStateException.class)
ResponseEntity<ErrorResponse> handleStateConflict(IllegalStateException ex, HttpServletRequest request) {
    log.atWarn()
            .addKeyValue("errorCode", "STATE_CONFLICT")
            .addKeyValue("path", request.getRequestURI())
            .addKeyValue("method", request.getMethod())
            .addKeyValue("exceptionClass", ex.getClass().getName())
            .addKeyValue("message", ex.getMessage())
            .addKeyValue("rootCauseClass", rootCauseClass(ex))
            .addKeyValue("rootCauseMessage", rootCauseMessage(ex))
            .log("State conflict");
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("STATE_CONFLICT", ex.getMessage(), Instant.now()));
}
```

Rules:

- Do not log request headers, cookies, Authorization, session id, email, name, or OAuth tokens.
- Log `path` and `method`; path may contain skill id but not credentials.
- Keep response shape unchanged: `{ error, message, timestamp }`.
- Add a small private root-cause helper only if needed; no new dependency.

POC: not required. Spring MVC official docs confirm `HttpServletRequest` can be an `@ExceptionHandler` argument, and current `GlobalExceptionHandlerTest` already exercises this handler family.

## 3. Acceptance Criteria

Verification command:

Run:

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest
```

Pass: all tests carrying S181 AC ids are green.

Production retest command after deploy:

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND textPayload:"State conflict" AND timestamp>="<deploy timestamp>"' --project=cfh-vibe-lab --limit=20
```

Pass: opening the Chrome validate URL produces an app log that includes path, method, exception class, and message for every new HTTP 409.

| AC | Priority | Verification | Title |
| --- | --- | --- | --- |
| AC-S181-1 | must | Test | 409 response body shape remains `STATE_CONFLICT` |
| AC-S181-2 | must | Test / log inspection | 409 log includes path and method |
| AC-S181-3 | must | Test / log inspection | 409 log includes exception class and message |
| AC-S181-4 | must | Manual LAB | Chrome validate 409 can be tied to Cloud Run app log |
| AC-S181-5 | should | Inspection | 409 log does not include secrets or identity PII |

### AC-S181-1 — 409 response body shape remains `STATE_CONFLICT`

Given（前提）controller/application code throws `IllegalStateException("synthetic conflict for S181")`

When（動作）Spring MVC invokes `GlobalExceptionHandler.handleStateConflict`

Then（結果）HTTP status is 409

And（而且）response body contains `error = "STATE_CONFLICT"`

And（而且）response body message is `synthetic conflict for S181`

### AC-S181-2 — 409 log includes path and method

Given（前提）request path is `/api/v1/me` and method is `GET`

When（動作）`IllegalStateException` is handled

Then（結果）WARN log includes `path=/api/v1/me`

And（而且）WARN log includes `method=GET`

### AC-S181-3 — 409 log includes exception class and message

Given（前提）`IllegalStateException("Failed to generate unique user_id after 5 retries")` is thrown

When（動作）`handleStateConflict` handles it

Then（結果）WARN log includes `exceptionClass=java.lang.IllegalStateException`

And（而且）WARN log includes `message=Failed to generate unique user_id after 5 retries`

### AC-S181-4 — Chrome validate 409 can be tied to Cloud Run app log

Given（前提）S181 is deployed to Cloud Run

When（動作）Chrome opens:

```text
https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

Then（結果）any new `/api/v1/me` or `/api/v1/skills/{id}` 409 has a corresponding application log line containing path, method, exception class, and message

And（而且）the log timestamp is close enough to the browser action to identify the root failing path.

### AC-S181-5 — 409 log does not include secrets or identity PII

Given（前提）an authenticated Chrome session triggers a 409

When（動作）Cloud Run application log is queried

Then（結果）the `State conflict` log does not include `Authorization`, cookies, session id, email, display name, picture URL, or OAuth subject

And（而且）it only includes path/method/exception metadata needed for debugging.

### NFR Coverage

| Category | Coverage | Reason |
| --- | --- | --- |
| Performance | N/A | Adds one WARN log only on exception path; no steady-state request cost. |
| Security | AC-S181-5 | Debug log must not leak auth/session/PII data. |
| Reliability | AC-S181-2, AC-S181-3, AC-S181-4 | Production 409 can be traced to exact root message instead of guessing. |
| Usability | N/A | No user-facing UI change in this spec. |
| Maintainability | AC-S181-1 | Keeps existing ErrorResponse contract stable while adding observability. |

## 4. Interface Design

No public API success-shape change.

Unchanged:

- `GET /api/v1/me` 200 response remains the 11-key `MeController` shape.
- HTTP 409 response remains:

```json
{
  "error": "STATE_CONFLICT",
  "message": "<exception message>",
  "timestamp": "<instant>"
}
```

Internal handler change:

| Method | Before | After |
| --- | --- | --- |
| `GlobalExceptionHandler.handleStateConflict` | `(IllegalStateException ex)` | `(IllegalStateException ex, HttpServletRequest request)` |

Expected log keys:

| Key | Example | Source |
| --- | --- | --- |
| `errorCode` | `STATE_CONFLICT` | constant in handler |
| `path` | `/api/v1/me` | `request.getRequestURI()` |
| `method` | `GET` | `request.getMethod()` |
| `exceptionClass` | `java.lang.IllegalStateException` | `ex.getClass().getName()` |
| `message` | `Failed to generate unique_user_id...` | `ex.getMessage()` |
| `rootCauseClass` | `org.springframework.dao.DataIntegrityViolationException` or empty | deepest cause |
| `rootCauseMessage` | database / conversion message or empty | deepest cause |

## 5. File Plan

| File | Action | Notes |
| --- | --- | --- |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` | modify | Add `HttpServletRequest` argument and structured log keys for `STATE_CONFLICT`; do not log headers/cookies/identity values. |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java` | modify | Add S181 test for response body stability and log key presence. |
| `docs/grimo/specs/spec-roadmap.md` | defer | Roadmap has pre-existing local diff; update after dirty state is split or reviewed. |

Task cut:

| Task | File(s) | Positive case | Negative case | POC |
| --- | --- | --- | --- | --- |
| T01 | `GlobalExceptionHandler.java`, `GlobalExceptionHandlerTest.java` | `/api/v1/me` synthetic state conflict logs path/method/class/message and still returns 409 body. | log capture must not contain Authorization/cookie/email/sub. | not required |
| T02 | LAB deploy evidence | Chrome validate 409 produces corresponding app log line with root message. | If no app log appears, logging backend/format must be corrected before guessing root fix. | not required |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
