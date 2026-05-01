# S052: HttpMessageNotReadableException → 400 INVALID_REQUEST_BODY

> Spec: S052 | Size: XS(5) | Status: ✅ Done — target ship `v2.29.0`
> Trigger: 2026-05-01 /loop tick 26 API probe — `POST /api/v1/skills/{id}/suspend` 不帶 body 時 response 含「Required request body is missing: org.springframework.http.ResponseEntity\<java.lang.Void\> io.github.samzhu.skillshub.skill.command.SkillCommandController.suspend(java.lang.String,io.github.samzhu.skillshub.skill.command.SkillCommandController$SuspendRequest)」— 完整 fully-qualified class name + 方法簽名 + 巢狀類別名外洩。Spring Boot 的預設 `HttpMessageNotReadableException` 訊息把 controller method handler 完整 toString 直接 echo 給 client。屬資訊洩漏（attacker 可掃描所有 endpoint 列出 internal class 結構）。

---

## 1. Goal

`GlobalExceptionHandler` 加 `@ExceptionHandler(HttpMessageNotReadableException.class)` → 400 + `INVALID_REQUEST_BODY` code + 固定 user-friendly message。所有「missing body」、「malformed JSON」場景統一不暴露 method 簽名。

---

## 2. Approach

### 2.1 Backend handler

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
    log.atWarn()
        .addKeyValue("errorCode", "INVALID_REQUEST_BODY")
        .addKeyValue("rawMessage", ex.getMessage())  // log 含 method sig 利 ops 排查
        .log("Unreadable request body");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("INVALID_REQUEST_BODY",
            "Request body is missing or malformed",
            Instant.now()));
}
```

### 2.2 Frontend i18n

```diff
 const ERROR_MESSAGES: Record<string, string> = {
   ...
+  INVALID_REQUEST_BODY: '請求內容缺失或格式錯誤，請重試。',
   ...
 }
```

### 2.3 為何 NOT 解析 ex.getCause() 給更精細錯誤

Jackson `JsonParseException` / `JsonMappingException` 等 cause chain 訊息含路徑 / line/column — 對 user 多沒意義；對 dev 可在 log 看 raw message。固定字串對 user 已 actionable（重檢 body）。

### 2.4 為何也涵蓋「malformed JSON」（T5 路徑）

T5 「invalid JSON」既有訊息「JSON parse error: Unrecognized token...」雖無 class 簽名，但仍是 Java 黑話 — 統一改友善訊息更一致。`HttpMessageNotReadableException` 是 Spring 對 Jackson 異常的統一 wrapper，一個 handler 涵蓋所有：missing body、invalid JSON、type mismatch。

---

## 3. SBE Acceptance Criteria

### AC-1: 不帶 body 的 POST 回 400 + clean message

```gherkin
When  POST /api/v1/skills/{id}/suspend without body
Then  HTTP 400
And   response body == {error: "INVALID_REQUEST_BODY", message: "Request body is missing or malformed", timestamp}
And   不包含 "ResponseEntity" / "java.lang." / 任何 class 簽名
```

### AC-2: malformed JSON 回 400 + clean message

```gherkin
When  POST /api/v1/skills with body "not valid json"
Then  HTTP 400
And   response body == {error: "INVALID_REQUEST_BODY", ...}
And   不包含 "JSON parse error" / "Unrecognized token"
```

### AC-3: frontend 顯繁中翻譯

```gherkin
When  user 觸發 INVALID_REQUEST_BODY 的場景
Then  顯示「請求內容缺失或格式錯誤，請重試。」
```

### AC-4: 合法 body 不影響

```gherkin
When  POST /api/v1/skills with valid body
Then  HTTP 201（既有路徑不破）
```

### AC-5: backend 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

### AC-6: frontend 既有 test 不破

```gherkin
When  npm test
Then  10 tests / 0 fail
```

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(HttpMessageNotReadableException.class)`

### 5.2 Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `INVALID_REQUEST_BODY` 翻譯

### 5.3 Test
- 既有 unit test 不破即可；E2E 由 curl 手測（4 個 AC）

### 5.4 Docs
- CHANGELOG `v2.29.0`
- spec-roadmap M48

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | handler + i18n + curl retest | AC-1~6 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.29.0`
>
> Verification: backend 286 / frontend 10 tests / 0 fail；E2E：missing body 123B clean / invalid JSON 同 / 不含 class 簽名 / 合法 body 仍 201。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-5 |
| `npm test` | 10 / 0 fail ✓ AC-6 |
| HTTP `POST .../suspend` no body | 400 / 123B / 不含 class 簽名 ✓ AC-1 |
| HTTP `POST /skills "not valid json"` | 400 / 123B / 不含 "JSON parse error" ✓ AC-2 |
| HTTP `POST /skills` valid body | 201 + skill id ✓ AC-4 |
| Frontend i18n | `INVALID_REQUEST_BODY: '請求內容缺失或格式錯誤，請重試。'` ✓ AC-3 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(HttpMessageNotReadableException.class)` + import

#### Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `INVALID_REQUEST_BODY` i18n entry

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: missing body 不洩 class 簽名 | ✅ PASS | 123B body / no leak grep PASS |
| AC-2: malformed JSON 統一格式 | ✅ PASS | 同 INVALID_REQUEST_BODY 訊息 |
| AC-3: frontend 顯繁中 | ✅ PASS | i18n map 接管 |
| AC-4: 合法 body 仍 201 | ✅ PASS | curl confirm |
| AC-5: 既有 backend test 不破 | ✅ PASS | 286 / 0 fail |
| AC-6: 既有 frontend test 不破 | ✅ PASS | vitest 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 26 API probe — `POST /api/v1/skills/{id}/suspend` 不帶 body 時 response 暴露完整 controller method 簽名「Required request body is missing: org.springframework.http.ResponseEntity\<java.lang.Void\> io.github.samzhu.skillshub.skill.command.SkillCommandController.suspend(java.lang.String,io.github.samzhu.skillshub.skill.command.SkillCommandController$SuspendRequest)」— attacker 可掃描所有 endpoint 列出 internal class + 巢狀類別結構。

**Fix scope choice — single handler covers 3 cases**:
- `HttpMessageNotReadableException` 是 Spring 對 Jackson exception 的統一 wrapper，涵蓋：
  - missing body（T2/T3/T4 觀察到的 method 簽名洩漏）
  - malformed JSON（T5「JSON parse error: Unrecognized token...」）
  - type mismatch（DTO 欄位型別不對）
- 一個 handler 對齊 3 場景，message 統一

**Defense-in-depth context**:
- 此 fix + S045（spring.web.error.include-stacktrace=never）+ S049（ZipException）+ S051（DuplicateKeyException）累積建立 backend default-error 防漏網
- 仍待補：T6 `Objects.requireNonNull` NPE → 500（屬不同 fix domain）

### 7.5 Pending Verification / Tech Debt

- **T6 餘留**：DTO 欄位 null 時 `Objects.requireNonNull(name, "name is required")` → NPE → 500（無 class 簽名洩漏 但 status code 錯）。應改 `IllegalArgumentException` 走 400 VALIDATION_ERROR；屬不同 fix domain 留下一輪
- semantic 系統性回 0 根因待查
- analytics「本週新增」算法待驗
