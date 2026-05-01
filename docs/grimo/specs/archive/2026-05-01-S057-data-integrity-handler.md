# S057: DataIntegrityViolationException Catch-All Handler

> Spec: S057 | Size: XS(5) | Status: ✅ Done — target ship `v2.34.0`
> Trigger: 2026-05-01 /loop tick 30 — `POST /api/v1/skills` 帶 100-char category（DB column varchar(50)）→ HTTP 500 + 暴露完整 SQL：「PreparedStatementCallback; SQL [INSERT INTO "skills" (...) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)]; ERROR: value too long for type character varying(50)」。S051 只攔 DuplicateKeyException 子類；其他 DataIntegrityViolation（length / NOT NULL / FK）仍 leak。

---

## 1. Goal

`GlobalExceptionHandler` 加 `@ExceptionHandler(DataIntegrityViolationException.class)` catch-all → 400 + `CONSTRAINT_VIOLATION` + 固定 friendly message。S051 既有 `DuplicateKeyException` handler 仍優先匹配（most-specific-first）。Frontend i18n 加翻譯。

---

## 2. Approach

### 2.1 Backend handler

```java
@ExceptionHandler(DataIntegrityViolationException.class)
ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
    log.atWarn()
        .addKeyValue("errorCode", "CONSTRAINT_VIOLATION")
        .addKeyValue("rawMessage", ex.getMessage())  // log 留 SQL detail 利 ops
        .log("Data integrity violation");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("CONSTRAINT_VIOLATION",
            "Submitted data exceeds allowed length or format constraints",
            Instant.now()));
}
```

### 2.2 為何 most-specific-first 不破 S051

Spring resolve `@ExceptionHandler` 用 most-specific-first。`DuplicateKeyException extends DataIntegrityViolationException` — DuplicateKey 拋時 S051 handler 命中（更具體）；其他 DataIntegrity（如 length / FK / NOT NULL）才落本 handler。

### 2.3 為何 status 400 而非 409

RFC 9110：
- 409 Conflict — 與當前 resource state 衝突（如 dup key）
- 400 Bad Request — input syntax / format 錯

「value too long」「FK violation」「NOT NULL」屬 input 沒符合 schema，更接近 400。S051 dup key 是 conflict-class 仍 409 不變。

### 2.4 為何 NOT 在 aggregate 加 per-field length cap

範圍守住 single fix point。aggregate 加 cap 雖更精確（user 看到「Category exceeds 50 chars」），但：
- 需 per-field 維護，DB schema 改 cap 時兩處同步
- DataIntegrity catch-all 已防漏，用戶仍知是輸入問題
- future XS spec 可逐欄加（如「category 50 cap」「author 255 cap」）

### 2.5 Frontend i18n

```diff
+CONSTRAINT_VIOLATION: '提交資料超過允許的長度或格式，請檢查後重試。',
```

---

## 3. SBE Acceptance Criteria

### AC-1: 超長 category → 400 不洩 SQL

```gherkin
When  POST /skills with category 長度 100 chars (DB cap=50)
Then  HTTP 400
And   response body == {error: "CONSTRAINT_VIOLATION", message: "Submitted data exceeds allowed length or format constraints", timestamp}
And   不包含 "PreparedStatementCallback" / "INSERT INTO" / SQL detail
```

### AC-2: dup name 仍走 S051 DUPLICATE_RESOURCE 不 regress

```gherkin
Given DB 已有 name="dup-test" 的 skill
When  POST /skills with name="dup-test"
Then  HTTP 409 + DUPLICATE_RESOURCE（既有 S051 不破）
```

### AC-3: 合法 input 仍 201

```gherkin
When  POST /skills with valid name/category
Then  HTTP 201
```

### AC-4: frontend 顯繁中

```gherkin
When  user 觸發 CONSTRAINT_VIOLATION
Then  顯示「提交資料超過允許的長度或格式，請檢查後重試。」
```

### AC-5: backend 286 tests 不破

### AC-6: frontend 10 tests 不破

---

## 4. Interface

詳 §2.1 / §2.5。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(DataIntegrityViolationException.class)`

### 5.2 Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `CONSTRAINT_VIOLATION` i18n entry

### 5.3 Test
- 既有 unit test 不破即可；E2E curl + Chrome i18n 驗

### 5.4 Docs
- CHANGELOG `v2.34.0`
- spec-roadmap M53

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | handler + i18n + curl + Chrome | AC-1~6 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.34.0`
>
> Verification: backend 286 / frontend 10 / 0 fail；E2E：long category 從 500 + SQL leak → 400 / 146B / 不含 SQL；dup key 仍 409 DUPLICATE_RESOURCE 不 regress。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-5 |
| `npm test` | 10 / 0 fail ✓ AC-6 |
| POST 100-char category | 400 + CONSTRAINT_VIOLATION / 146B / no SQL leak ✓ AC-1 |
| POST 重複 name | 409 + DUPLICATE_RESOURCE（S051 不 regress）✓ AC-2 |
| POST 合法 input | 201 ✓ AC-3 |
| Frontend i18n | `CONSTRAINT_VIOLATION: '提交資料超過允許的長度或格式，請檢查後重試。'` ✓ AC-4 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(DataIntegrityViolationException.class)` + import

#### Frontend (1 file)
- `frontend/src/lib/api-error-messages.ts`：加 `CONSTRAINT_VIOLATION` i18n entry

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: long category 不洩 SQL | ✅ |
| AC-2: dup name 仍 409（S051 不破）| ✅ |
| AC-3: 合法 201 | ✅ |
| AC-4: i18n 翻譯 | ✅ |
| AC-5: backend test 不破 | ✅ |
| AC-6: frontend test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 30 probe — 100-char category（DB column varchar(50)）→ 500 + 完整 SQL exception「value too long for type character varying(50)」。S051 只攔 DuplicateKeyException 子類；其他 DataIntegrity（length / NOT NULL / FK）仍 leak。

**Most-specific-first 設計**:
- `DuplicateKeyException` (S051) → 409 DUPLICATE_RESOURCE（subclass，優先）
- `DataIntegrityViolationException` (S057) → 400 CONSTRAINT_VIOLATION（catch-all parent）

**Defense-in-depth 累積成果**：
- S045: Spring fallback errors strip stacktrace + 405 normalized
- S049: ZipException → 400 VALIDATION_ERROR
- S051: DuplicateKey → 409 DUPLICATE_RESOURCE
- S052: HttpMessageNotReadable → 400 INVALID_REQUEST_BODY
- S057: DataIntegrityViolation catch-all → 400 CONSTRAINT_VIOLATION
- 累計 5 層 backend default-error 防漏網

**Status code 選 400 而非 409 reasoning**：
- RFC 9110 — value too long / FK / NOT NULL 屬 input 不符合 schema → 400
- 409 Conflict 限 「conflict with current state」（如 S051 dup key）

### 7.5 Pending Verification / Tech Debt

- 可進一步在 aggregate 加 per-field length cap 給 user 更精確訊息（如「Category exceeds 50 chars」）— XS spec 候選
- semantic 系統性回 0 根因仍待查
- DB 既有畸形 entries（包含 tick 28 ACL + tick 29 version "foo"/"" 等）需 migration 清理
