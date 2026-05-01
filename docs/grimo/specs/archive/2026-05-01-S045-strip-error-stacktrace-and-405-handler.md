# S045: Strip Default Error Stack Trace + 405 Handler

> Spec: S045 | Size: XS(5) | Status: ✅ Done — target ship `v2.22.0`
> Trigger: 2026-05-01 /loop tick 19 §7.5 — `POST /api/v1/skills/{id}/versions`（method not allowed）回 12.9KB body 含 `LabSecurityFilter` / Spring Security filter chain class names 完整 stack trace。tick 20 進一步確認 `DELETE /api/v1/skills`（405）/ POST text-plain（415）/ 未知 path（404）所有未被 `GlobalExceptionHandler` 攔截的 Spring 預設錯誤都洩漏 12-14KB stack trace。

---

## 1. Goal

關閉 Spring 預設 `BasicErrorController` 的 stack trace 輸出（`server.error.include-stacktrace: never`）+ 為 `HttpRequestMethodNotSupportedException` 加 `@ExceptionHandler` → 405 METHOD_NOT_ALLOWED + normalize 為 `{error, message, timestamp}` ErrorResponse 格式。

---

## 2. Approach

### 2.1 application.yaml diff

```diff
+# ----- 錯誤回應安全（S045）-----
+# 預設關閉 stack trace + exception class name 暴露：未被 GlobalExceptionHandler
+# 攔截的錯誤（如 405 / 415 / unknown path 404）落 BasicErrorController 預設格式時，
+# response body 不會包含 12-14KB Spring filter chain class names。
+server:
+  error:
+    include-stacktrace: never
+    include-exception: false
+    include-message: always
+    include-binding-errors: never
```

### 2.2 GlobalExceptionHandler 加 405 handler

```diff
+@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
+ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
+    log.atWarn()
+        .addKeyValue("errorCode", "METHOD_NOT_ALLOWED")
+        .addKeyValue("method", ex.getMethod())
+        .log("HTTP method not supported");
+    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
+        .body(new ErrorResponse("METHOD_NOT_ALLOWED",
+            "HTTP method '" + ex.getMethod() + "' is not supported for this resource",
+            Instant.now()));
+}
```

### 2.3 為何 NOT 同時加 415 / NoResourceFoundException 404 handler

範圍守住 XS：
- 415 / unknown-path 404 屬罕見場景（client misuse）；yaml include-stacktrace=never 已關漏洞
- Bug 觸發點是 405（user 測試可達）；405 normalize 對齊既有 ErrorResponse 風格
- 留 415 / 404-no-resource handler 為 future spec（同檔模式可一行一行補）

### 2.4 為何 NOT 改 Spring 的 default error path（`/error`）

`server.error.path` 改路徑或自寫 ErrorController 屬大改動；本 spec 走「上游攔（GlobalExceptionHandler）+ 下游遮（include-stacktrace=never）」雙層；既保留 Spring 預設 fallback 行為，又確保不洩漏。

---

## 3. SBE Acceptance Criteria

### AC-1: 405 不再噴 stack trace

```gherkin
Given 已 ship S045
When  POST /api/v1/skills/{id}/versions（endpoint 不接受 POST，僅 GET）
Then  HTTP 405
And   response body 為 {error: "METHOD_NOT_ALLOWED", message, timestamp}
And   response size < 500 bytes（之前 12.9KB）
And   body 不含 "LabSecurityFilter" 或 "FilterChainProxy" 等 class name
```

### AC-2: 415 不再噴 stack trace（落 Spring 預設 path 但 trace 被 strip）

```gherkin
When  POST /api/v1/skills with Content-Type: text/plain
Then  HTTP 415
And   response body 不含 "trace" field
And   response size < 1KB（之前 13.9KB）
```

### AC-3: 404 unknown path 不再噴 stack trace

```gherkin
When  GET /api/v1/totally-bogus
Then  HTTP 404
And   response body 不含 "trace" field
And   response size < 1KB（之前 12.7KB）
```

### AC-4: 既有 GlobalExceptionHandler 處理的錯誤格式不變

```gherkin
When  POST /api/v1/skills with name="BAD_UPPER" 觸發 IllegalArgumentException
Then  HTTP 400
And   response body == {error: "VALIDATION_ERROR", message, timestamp}（既有格式不變）
```

### AC-5: 既有 unit test 不破

```gherkin
When  ./gradlew test
Then  286 tests 全綠
```

### AC-6: 既有正常 endpoint 不破

```gherkin
When  GET /api/v1/skills
Then  HTTP 200 + 25 skills（既有 happy path 不破）
```

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (2 files)
- `backend/src/main/resources/application.yaml`：加 `server.error.include-stacktrace: never` 等 4 個 toggle
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `handleMethodNotAllowed`（@ExceptionHandler HttpRequestMethodNotSupportedException）

### 5.2 Test
- E2E 由 manual curl 驗（4 個 AC，response size + 不含 class name）；既有 unit test 不破即可

### 5.3 Docs
- CHANGELOG `v2.22.0`
- spec-roadmap M41

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | yaml + handler + E2E retest | AC-1~6 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.22.0`
>
> Verification: `./gradlew test` 286 tests / 0 fail；E2E HTTP 405/415/404 size 從 12-14KB 收斂至 138-180B + 完全不含 stack trace；既有 GlobalExceptionHandler 處理路徑（VALIDATION_ERROR / SKILL_SUSPENDED 等）格式不變。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL；286 tests / 0 failures / 0 errors |
| HTTP `POST .../versions`（405）| 138B；body `{error,message,timestamp}` 含 `METHOD_NOT_ALLOWED` ✓ AC-1（baseline 12.9KB）|
| HTTP `POST /skills text/plain`（415）| 180B；body 不含 `trace` ✓ AC-2（baseline 13.9KB）|
| HTTP `GET /api/v1/totally-bogus`（404）| 157B；body 不含 `trace` ✓ AC-3（baseline 12.7KB）|
| HTTP `POST /skills name=BAD_UPPER`（既有 400 path）| `{error:"VALIDATION_ERROR", message, timestamp}` 既有格式不變 ✓ AC-4 |
| HTTP `GET /skills?size=1` | 200 + 25 skills ✓ AC-6 |

### 7.2 Files Changed

#### Backend (2 files)
- `backend/src/main/resources/application.yaml`：加 `spring.web.error.{include-stacktrace, include-exception, include-message, include-binding-errors}` 4 toggle（merged 進既有 `spring:` 區塊避免 YAML duplicate-key 問題）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(HttpRequestMethodNotSupportedException.class) handleMethodNotAllowed`

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 405 不噴 trace + normalize ErrorResponse | ✅ PASS | 138B body + `METHOD_NOT_ALLOWED` code |
| AC-2: 415 不噴 trace（落 BasicErrorController + yaml strip）| ✅ PASS | 180B / 不含 `trace` field |
| AC-3: 404 unknown path 不噴 trace | ✅ PASS | 157B / 不含 `trace` field |
| AC-4: 既有 GlobalExceptionHandler 路徑不變 | ✅ PASS | VALIDATION_ERROR 格式照舊 |
| AC-5: 既有 unit test 不破 | ✅ PASS | 286 / 0 fail |
| AC-6: 既有 happy path 不破 | ✅ PASS | 200 + 25 skills |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 19 §7.5 + tick 20 baseline probe — Spring Boot fallback errors（405/415/404 unknown path）leak 12-14KB stack trace 含 `LabSecurityFilter`、`FilterChainProxy`、Spring Security/Tomcat internal class names；屬資訊洩漏類。

**Spring Boot 4 property rename surprise**:
- 初次嘗試 `server.error.include-stacktrace: never`（Spring Boot 3.x 標準）— 完全沒生效，415/404 仍噴 trace
- 從 `spring-boot-autoconfigure-4.0.6.jar` 內 `META-INF/spring-configuration-metadata.json` 確認 Spring Boot 4 已重命名為 `spring.web.error.*`
- yaml parse 沿用舊 prefix 不會報錯 — 完全 silent；必須用新 prefix 才生效
- 此處寫進 yaml 註解防未來再踩

**Fix design rationale**:
- yaml 全局 strip 為 defense-in-depth — 即使其他未攔截錯誤落 BasicErrorController 也不噴 trace
- 405 加 explicit handler 讓格式對齊 `{error, message, timestamp}` ErrorResponse；frontend 可走既有 `localizeApiError` Record map 翻譯
- 415 / NoResourceFoundException 404 仍走 Spring 預設 path（`{timestamp, status, error, message, path}`）— 無 trace 已足夠安全；future spec 可進一步 normalize

### 7.5 Pending Verification / Tech Debt

- **Bug C（同 tick 20 發現）**：`HomePage` semantic search 回 0 結果（不是 error）時停在死巷「未找到匹配的技能 試試換個描述方式」，沒 fallback 至 keyword search。dev 環境 `SPRING_PROFILES_ACTIVE=local` override 預設 `local,dev` 不載入 application-secrets.properties → embedding model disabled → 任何 query 都回 0；prod 環境 embedding 真的找不到時 user 也卡住。屬 frontend UX bug — 留下一輪 spec
- **placeholder 過時**：搜尋框 `placeholder="搜尋技能名稱或描述..."` 未含 category（S043 後）— UI copy 微調
- 415 / NoResourceFoundException 404 normalize 至 `{error, message, timestamp}`（目前格式不一致但安全）
- S031 §7.5 admin panel endpoint 仍待設計
