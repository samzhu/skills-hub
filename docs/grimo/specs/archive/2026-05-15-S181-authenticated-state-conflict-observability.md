# S181 — Authenticated State Conflict Observability

> SpecID: S181
> Status: ✅ shipped v4.64.0
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
| `rootCauseClass` | `org.springframework.dao.DataIntegrityViolationException` or `java.lang.IllegalStateException` | deepest cause; same as exception if no nested cause |
| `rootCauseMessage` | database / conversion message or exception message | deepest cause; same as exception message if no nested cause |

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
| T02 | LAB deploy evidence | Chrome validate retest either produces corresponding app log line for each new 409, or proves no new 409 is being produced after S181 deploy. | If no app log appears while request log still shows 409, logging backend/format must be corrected before guessing root fix. | not required |

---

## 6. Task Plan

| Task | Status | BDD / verification |
| --- | --- | --- |
| S181-T01 — STATE_CONFLICT log evidence | PASS | Given `GET /api/v1/me` throws `IllegalStateException`, when `GlobalExceptionHandler` handles it, then response stays HTTP 409 `STATE_CONFLICT` and WARN log includes path/method/exception/root cause without Authorization/cookie/identity fields. Verified by `./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest`. |
| S181-T02 — Deploy and collect LAB evidence | PASS | Given S181-T01 is deployed, when Chrome opens the validate URL, then Cloud Run logs either show exact path/method/exception/message for each new 409 or prove the 409 no longer occurs. Verified on `skillshub-00030-rd2`: `/api/v1/me` returned 200 after login; skill detail and bundle-info returned 403; no new `State conflict` log appeared because no 409 occurred. |

Roadmap update remains deferred because `docs/grimo/specs/spec-roadmap.md` had pre-existing unrelated local changes before S181-T01 started.

## 7. Implementation Results

### S181-T01 — PASS

Date: 2026-05-16

Files changed:

- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java`
- `docs/grimo/tasks/2026-05-15-S181-T01-state-conflict-log-evidence.md`

Red result:

```text
./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest
compileTestJava FAILED: handleStateConflict required IllegalStateException but test passed IllegalStateException, MockHttpServletRequest.
```

Green result:

```text
./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest
BUILD SUCCESSFUL in 2m 20s
```

AC result:

| AC | Result |
| --- | --- |
| AC-S181-1 | PASS — test confirms 409 response body remains `STATE_CONFLICT`. |
| AC-S181-2 | PASS — test captures WARN log containing `/api/v1/me` and `GET`. |
| AC-S181-3 | PASS — test captures exception class/message and root cause class/message. |
| AC-S181-4 | PASS via S181-T02 — deploy and Chrome + Cloud Run retest proved no new 409 occurs on the validate path. |
| AC-S181-5 | PASS local — test confirms handler output does not include Authorization, cookie, email, or OAuth subject fixture strings. |

### S181-T02 — PASS

Date: 2026-05-16

Files changed:

- `docs/grimo/tasks/2026-05-15-S181-T02-deploy-log-evidence.md`
- `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`

Build result:

```text
gcloud builds submit --config=cloudbuild.yaml --project=cfh-vibe-lab --substitutions=_REGION=asia-east1,_TAG=20260515-190626
ID b968943e-4f63-41db-aa8c-cee68fdaa963
IMAGE asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260515-190626
STATUS SUCCESS
```

Deploy result:

```text
gcloud run services replace temp/service.rendered.yaml --region=asia-east1 --project=cfh-vibe-lab --quiet
New revision: skillshub-00030-rd2
Revision status: Ready
Startup probe: /actuator/health/readiness succeeded after 1 attempt
```

Deploy note:

- First attempt used `scripts/gcp/04-deploy.sh` from the clean build snapshot and produced failed revision `skillshub-00029-2kc`.
- `skillshub-00029-2kc` failed startup because the generated repo manifest did not include the production OAuth client env, `spring.config.additional-location`, DB env, Secret Manager `/config` volume, Direct VPC egress annotations, and Cloud SQL proxy `--private-ip`.
- The second attempt followed the automation prompt by updating only the app image in `temp/service.rendered.yaml`; `skillshub-00030-rd2` started with the expected OAuth/Secret/VPC settings.

Chrome / Cloud Run retest:

| Step | Result |
| --- | --- |
| Chrome opened `/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5` before clicking login | Page showed `登入` and `無法載入 skill`; Cloud Run returned 401 for `/api/v1/me`, skill detail, and bundle-info. |
| Chrome clicked `登入` and returned to the validate URL | Page no longer showed `登入`; `UserUpsertService` logged `user refreshed userId=u_5e0652 provider=google`. |
| Chrome reloaded validate URL after login | Page still showed `無法載入 skill`; Cloud Run returned `/api/v1/me` 200, `/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5` 403, and `/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info` 403. |
| Cloud Logging query for `textPayload:"State conflict"` after deploy | Returned `[]`; no new 409 occurred in the authenticated retest, so S181 handler was not triggered. |

AC result:

| AC | Result |
| --- | --- |
| AC-S181-1 | PASS — unchanged from T01. |
| AC-S181-2 | PASS — unchanged from T01. |
| AC-S181-3 | PASS — unchanged from T01. |
| AC-S181-4 | PASS — production retest proved the original authenticated 409 no longer occurs on this path after S181 deploy; the remaining validate failure is authenticated 403, not state conflict. |
| AC-S181-5 | PASS — production `State conflict` query returned no rows; the deployed code path also does not log headers/cookies/identity fields per T01 test. |

Follow-up finding:

- The next clear work unit should plan a small ACL/visibility debug spec for the authenticated 403 on skill detail and bundle-info for `028cecf1-3326-4327-bbe9-28b4e6fab6d5`.
- Evidence to start from: after login, `/api/v1/me` returned 200 and `UserUpsertService` refreshed `userId=u_5e0652`; the two failing requests returned 403 at `2026-05-15T19:22:17Z` on revision `skillshub-00030-rd2`.

### Shipping Preflight — BLOCKED (2026-05-16)

`$shipping-release` preflight was checked before archiving S181. Gate 3 requires `git status` to be clean of unrelated changes before updating roadmap, changelog, archive paths, or tags.

Tick-start state:

```text
git status --short
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md
```

Result: BLOCKED. The modified roadmap and untracked S179 spec were already present before this tick and are not S181 release files. Shipping S181 now would require touching `docs/grimo/specs/spec-roadmap.md`, so this tick must not stage release metadata around those unrelated edits.

Pending release actions after the dirty state is split or cleaned:

- Run `./scripts/verify-all.sh` in the same tick as release.
- Move this spec to `docs/grimo/specs/archive/`.
- Delete `docs/grimo/tasks/2026-05-15-S181-*.md`.
- Update `docs/grimo/CHANGELOG.md` and the S181 row in `docs/grimo/specs/spec-roadmap.md`.

### Full-Suite Stability Fix — PASS (2026-05-16)

S175 shipping preflight exposed a full-suite failure in the S181 test:

```text
GlobalExceptionHandlerTest > AC-S181-1/2/3/5: IllegalStateException 409 logs request metadata and preserves response body FAILED
java.lang.AssertionError at GlobalExceptionHandlerTest.java:470
```

Root cause: the test asserted the S181 structured log by reading the whole captured console
string through `CapturedOutput`. In the full backend suite, that made the assertion depend on
when the log line had been flushed into the shared captured output buffer. The production
code already emitted the `STATE_CONFLICT` key-value fields; the unstable part was the test
observation method.

Fix:

- `GlobalExceptionHandlerTest.stateConflictLogsRequestMetadataAndPreservesResponse` now
  attaches a Logback `ListAppender<ILoggingEvent>` directly to the
  `GlobalExceptionHandler` logger.
- The test asserts the actual logging event message and key-value pairs:
  `errorCode`, `path`, `method`, `exceptionClass`, `message`, `rootCauseClass`,
  `rootCauseMessage`.
- The same event assertion still checks the log payload does not include Authorization,
  bearer token, cookie, session id, email, or OAuth subject fixture strings.

Verification:

```text
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest
BUILD SUCCESSFUL in 1m 59s

cd backend && ./gradlew clean test jacocoTestReport
BUILD SUCCESSFUL in 4m 40s
```

Result: PASS. The S181 full-suite blocker recorded by S175 is fixed.

### Release Result — PASS (2026-05-16)

S181 shipped in `v4.64.0` together with S175 because the unstable S181 log assertion was
the only current full-suite blocker for the S175 release.

Final verification:

```text
./scripts/verify-all.sh
PASS — V01=PASS, V02=INFO line coverage 85.9%, V03=PASS, V04/V05/V06/V07=SKIP
because prerequisites were not met in the clean release worktree, V08a=PASS, V08b=PASS,
Verdict: all CRITICAL passed; exit=0.
```

Release summary:

- `GlobalExceptionHandler.handleStateConflict` keeps the HTTP 409
  `STATE_CONFLICT` response shape unchanged.
- The handler logs `path`, `method`, exception class/message, and root-cause
  class/message without Authorization, bearer token, cookie, session id, email, or
  OAuth subject values.
- The production Chrome retest no longer reproduced the original authenticated 409:
  `/api/v1/me` returned 200, while the remaining validate failure returned 403 and was
  recorded as a separate follow-up finding.
- The full backend suite blocker was fixed by reading the actual Logback event instead
  of the shared captured console buffer.

### Final Size Re-score

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 1 | Spring MVC supports `HttpServletRequest` in `@ExceptionHandler`; implementation matched the documented mechanism. |
| Uncertainty | 1 | 2 | Production retest changed the observed failure from 409 to 403, so S181 became observability plus follow-up evidence. |
| Dependencies | 0 | 1 | Needed Cloud Run deploy/log evidence and Chrome authenticated retest. |
| Scope | 1 | 1 | Production code stayed in one handler plus one focused test file. |
| Testing | 2 | 2 | Covered targeted handler tests, backend full suite, and production log verification. |
| Reversibility | 1 | 1 | Additive log fields and tests can be removed or narrowed without API contract migration. |
| **Total** | **6 / XS** | **8 / S** | Bucket shifts XS→S because deploy/log evidence and full-suite test stabilization were required before release. |
