# S051: DuplicateKeyException → 409 DUPLICATE_RESOURCE

> Spec: S051 | Size: XS(5) | Status: ✅ Done — target ship `v2.28.0`
> Trigger: 2026-05-01 /loop tick 25 API probe — `POST /api/v1/skills` with name 與既有 skill 重複時，DB unique constraint 違反 → `DuplicateKeyException` 落 Spring 預設 500，response body 暴露完整 SQL：「PreparedStatementCallback; SQL [INSERT INTO "skills" ("acl_entries", "author", "category", ...) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)]; ERROR: duplicate key value violates unique constraint "skills_name_key"\n  詳細：Key (name)=(tick23-testing-skill) already exists.」屬：(1) 資訊洩漏 — DB schema 整張表 column 列出 + constraint 名稱外洩；(2) 錯誤狀態碼 — RFC 9110 §15.5.10 unique violation 屬 409 Conflict；(3) user-facing 訊息全 SQL 黑話。

---

## 1. Goal

`GlobalExceptionHandler` 加 `@ExceptionHandler(DuplicateKeyException.class)` → 409 Conflict + `DUPLICATE_RESOURCE` code + 固定 user-friendly message；frontend i18n map 加 `DUPLICATE_RESOURCE` 繁中翻譯。

---

## 2. Approach

### 2.1 Backend handler

```java
@ExceptionHandler(DuplicateKeyException.class)
ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
    log.atWarn()
        .addKeyValue("errorCode", "DUPLICATE_RESOURCE")
        .addKeyValue("rawMessage", ex.getMessage())  // log 留 SQL detail 利 ops 排查
        .log("Duplicate key violation");
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse("DUPLICATE_RESOURCE",
            "A resource with the same identifier already exists",
            Instant.now()));
}
```

### 2.2 Frontend i18n

```diff
 const ERROR_MESSAGES: Record<string, string> = {
   ...
+  DUPLICATE_RESOURCE: '此名稱已被使用，請換一個名稱。',
   ...
 }
```

### 2.3 為何 NOT 解析 SQL constraint name 給更精確錯誤

`DuplicateKeyException.getMessage()` 含 `skills_name_key` 等 constraint name；解析它再 case-by-case map 到「name 已存在 / version 已存在 / ...」屬 over-engineering：
- MVP 階段唯一的 unique constraint 是 skill name；新加 constraint 時 spec 自然會更新 i18n
- 訊息「此名稱已被使用」對 user 已足夠 actionable（去改 name）
- 不解析 SQL 訊息 = 不依賴 DB error 文字格式（不同 DB / locale 不一致）

### 2.4 為何 NOT 在 Aggregate 內預先檢查 name 唯一

過早 lookup + race condition：
- 「先 SELECT 看是否存在 → 不存在才 INSERT」屬 TOCTOU；高並發兩 request 都通過 SELECT 後同時 INSERT 仍會 violates constraint
- 既然 DB constraint 是 source of truth，handler 接 exception 為正解；應用層不需重複守
- defense-in-depth：constraint 為 last line；application 守 invariant 在 aggregate factory（per S041）

---

## 3. SBE Acceptance Criteria

### AC-1: 重複 name 上傳回 409 + DUPLICATE_RESOURCE

```gherkin
Given DB 已有 name="dup-test" 的 skill
When  POST /api/v1/skills with name="dup-test"
Then  HTTP 409
And   response body == {error: "DUPLICATE_RESOURCE", message: "A resource with the same identifier already exists", timestamp}
And   不包含 "INSERT INTO" / SQL detail / constraint 名稱
```

### AC-2: frontend 顯繁中翻譯

```gherkin
When  user 上傳重複 name 的 skill via PublishPage
Then  顯示「此名稱已被使用，請換一個名稱。」
And   不顯示原 SQL 訊息
```

### AC-3: 既有 unique 場景不破

```gherkin
When  POST /api/v1/skills with new name
Then  HTTP 201 + skill id（既有路徑不破）
```

### AC-4: 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

### AC-5: frontend 既有測試不破

```gherkin
When  npm test
Then  既有 vitest 全綠
```

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(DuplicateKeyException.class)`

### 5.2 Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `DUPLICATE_RESOURCE` 翻譯

### 5.3 Test
- 既有 unit test 不破即可；E2E 由 curl + Chrome 手測（5 個 AC）

### 5.4 Docs
- CHANGELOG `v2.28.0`
- spec-roadmap M47

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | handler + i18n + curl + Chrome retest | AC-1~5 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.28.0`
>
> Verification: backend 286 / frontend 10 tests / 0 fail；E2E：duplicate name 從 500（SQL leak）→ 409 / 135B body / 不含 SQL detail；new name 仍 201。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 tests / 0 fail ✓ AC-4 |
| `npm test -- --run` | 10 tests / 0 fail ✓ AC-5 |
| HTTP `POST /skills` 重複 name | 409 + `{error: "DUPLICATE_RESOURCE", message: "A resource with the same identifier already exists"}` 135B；不含 SQL ✓ AC-1 |
| HTTP `POST /skills` 新 name | 201 + `{id}` ✓ AC-3 |
| Chrome i18n（既有 path） | `localizeApiError` 走 ERROR_MESSAGES 顯「此名稱已被使用，請換一個名稱。」✓ AC-2 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(DuplicateKeyException.class)` + import

#### Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `DUPLICATE_RESOURCE` i18n entry

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 重複 name 回 409 + clean format | ✅ PASS | 135B body 不含 SQL |
| AC-2: frontend 顯繁中 | ✅ PASS | i18n map 接管 |
| AC-3: 新 name 仍 201 | ✅ PASS | curl confirm |
| AC-4: 既有 backend test 不破 | ✅ PASS | 286 / 0 fail |
| AC-5: 既有 frontend test 不破 | ✅ PASS | vitest 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 25 API probe — POST /api/v1/skills with 重複 name 回 HTTP 500 + 完整 SQL exception 訊息（INSERT 語句、所有 column、constraint name "skills_name_key"、PostgreSQL 中文「詳細：Key (name)=...」）。三層 bug：
- 資訊洩漏 — DB schema 整張表 column 列出 + constraint 名稱
- 錯誤狀態碼 — RFC 9110 §15.5.10 unique violation 屬 409 Conflict
- user-facing 訊息全 SQL 黑話

**Fix design rationale**:
- 固定 user-friendly message — 不暴露 ex.getMessage() 內 SQL detail
- raw `ex.getMessage()` 留 log 利 ops 排查（not lost）
- 不解析 SQL constraint 名給「name 已存在 / version 已存在」case-by-case — MVP 階段唯一 unique constraint 是 skill name；簡訊「此名稱已被使用」對 user 已 actionable
- 不在 Aggregate 內預先 SELECT 看 name 是否存在 — TOCTOU race；DB constraint 是 source of truth

### 7.5 Pending Verification / Tech Debt

- semantic 系統性回 0 根因待查
- 415 / NoResourceFoundException 404 normalize 至 ErrorResponse 格式（無 trace 已安全；格式統一性為小問題）
- analytics「本週新增」算法待驗
