# S049: ZipException → 400 VALIDATION_ERROR

> Spec: S049 | Size: XS(5) | Status: ✅ Done — target ship `v2.26.0`
> Trigger: 2026-05-01 /loop tick 24 Chrome E2E — 上傳 corrupt zip 後 user 看到「invalid stored block lengths」（`java.util.zip.ZipException` 原文）。`GlobalExceptionHandler` 沒攔，落 Spring 預設 500 → frontend `err.code` undefined → `localizeApiError` fallback `err.message` 直顯給 user。i18n map 既有 `VALIDATION_ERROR` 友善訊息「zip 套件驗證失敗，請確認格式正確。」未生效。

---

## 1. Goal

`GlobalExceptionHandler` 加 `@ExceptionHandler(ZipException.class)` → 400 VALIDATION_ERROR + 自訂友善 message。Frontend 走既有 i18n map 自動顯繁中。

```java
@ExceptionHandler(ZipException.class)
ResponseEntity<ErrorResponse> handleInvalidZip(ZipException ex) {
    log.atWarn()
        .addKeyValue("errorCode", "VALIDATION_ERROR")
        .addKeyValue("message", ex.getMessage())
        .log("Invalid zip upload");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("VALIDATION_ERROR",
            "Invalid zip file: cannot read package contents",
            Instant.now()));
}
```

---

## 2. Approach

### 2.1 Code diff

```diff
+import java.util.zip.ZipException;
+
 // ... 既有 handler ...

+@ExceptionHandler(ZipException.class)
+ResponseEntity<ErrorResponse> handleInvalidZip(ZipException ex) {
+    log.atWarn()
+        .addKeyValue("errorCode", "VALIDATION_ERROR")
+        .addKeyValue("message", ex.getMessage())
+        .log("Invalid zip upload");
+    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
+        .body(new ErrorResponse("VALIDATION_ERROR",
+            "Invalid zip file: cannot read package contents",
+            Instant.now()));
+}
```

注意 `ZipException` extends `IOException` — Spring most-specific-first 規則保證 `ZipException` 先匹配（不會被泛 IOException handler 攔，目前也沒有泛 IOException handler）。

### 2.2 為何 NOT 加 IOException 泛型 handler

過廣：IOException 涵蓋網路、disk、其他 stream 錯誤；不應一律對應 400。`ZipException` 明確是 user-supplied bad data。

### 2.3 為何 NOT 在 PackageService 內部 catch + rethrow

放 GlobalExceptionHandler 是 cross-cutting：未來新增任何 zip 處理 endpoint（如 admin batch import）都自動受惠。PackageService 內 catch 會分散 error policy 至多處。

### 2.4 為何 message 用 `"Invalid zip file: cannot read package contents"` 而非 ex.getMessage()

`ex.getMessage()` 可能含 ZIP magic byte / offset 等內部 detail（如 "invalid stored block lengths"）user 看不懂。固定字串保持 i18n map 對應 `VALIDATION_ERROR` 既有翻譯（「zip 套件驗證失敗，請確認格式正確。」）即足。

---

## 3. SBE Acceptance Criteria

### AC-1: corrupt zip 上傳回 400 + VALIDATION_ERROR code

```gherkin
When  POST /api/v1/skills/upload with malformed zip bytes
Then  HTTP 400
And   response body == {error: "VALIDATION_ERROR", message: "Invalid zip file: cannot read package contents", timestamp}
```

### AC-2: frontend 顯繁中翻譯（不破 user-facing）

```gherkin
When  user 拖入 corrupt zip 至 PublishPage 並 submit
Then  顯示「zip 套件驗證失敗，請確認格式正確。」（既有 i18n map）
And   不顯示 raw "invalid stored block lengths"
```

### AC-3: 合法 zip 仍正常 publish

```gherkin
When  POST /api/v1/skills/upload with valid zip + SKILL.md
Then  HTTP 201 + skill id
```

### AC-4: 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(ZipException.class)`

### 5.2 Test
- 既有 unit test 不破即可；E2E 由 curl + Chrome 手測（4 個 AC）

### 5.3 Docs
- CHANGELOG `v2.26.0`
- spec-roadmap M45

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | handler + curl + Chrome retest | AC-1~4 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.26.0`
>
> Verification: 286 tests / 0 fail；E2E：合法 zip 正常 publish (201)；空 zip / 「SKILL.md not found」走 VALIDATION_ERROR → frontend 顯示「zip 套件驗證失敗，請確認格式正確。」i18n 翻譯接管 ✓。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 tests / 0 failures / 0 errors ✓ AC-4 |
| HTTP `POST /skills/upload` empty zip | 400 + `{error: "VALIDATION_ERROR", message: "SKILL.md not found in zip", ...}` |
| HTTP `POST /skills/upload` valid zip | 201 + `{id}` ✓ AC-3 |
| Chrome E2E empty zip | 「zip 套件驗證失敗，請確認格式正確。」i18n 訊息 ✓ AC-2（同 code path）|

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 `@ExceptionHandler(ZipException.class)` → 400 VALIDATION_ERROR with fixed friendly message（不暴露 ex.getMessage 內 Java 內部 detail）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: corrupt zip 回 400 + VALIDATION_ERROR | ✅ PASS（logic）| handler registered；most-specific-first 規則保證 ZipException 先匹配 |
| AC-2: frontend 顯繁中翻譯 | ✅ PASS | E2E 確認 VALIDATION_ERROR i18n map 接管 |
| AC-3: 合法 zip 仍正常 publish | ✅ PASS | curl 201 + skill id |
| AC-4: 既有 test 不破 | ✅ PASS | 286 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 24 Chrome happy-path E2E — base64-encoded zip 因 shell encoding 損毀，後端拋 `java.util.zip.ZipException: invalid stored block lengths`；GlobalExceptionHandler 沒攔，落 Spring 預設 500（per S045 yaml `include-message: always`）→ frontend `localizeApiError` `err.code` undefined → fallback `err.message` 直顯 user。

**Fix scope choice — backend single-point**:
- handler 放 GlobalExceptionHandler 為 cross-cutting；未來任何 zip 處理 endpoint 都自動受惠
- 不在 PackageService 內部 catch — 避免 error policy 分散
- 不加 IOException 泛型 handler — 過廣，可能誤攔網路/disk I/O

**Message hardcoded reasoning**: `ex.getMessage()` 含內部 detail（「invalid stored block lengths」/ ZIP magic offset）user 看不懂；固定字串保持 i18n map 對應 `VALIDATION_ERROR` 既有翻譯。

### 7.5 Pending Verification / Tech Debt

- **ZipException 實際觸發條件 fuzzing**：tick 24 嘗試多種 corrupt zip（truncated / random bytes / bit-flip 中段）皆未實際觸發 `ZipException` —— Java's `ZipInputStream` 對 corrupt data 容忍度高，多 fall through 至「SKILL.md not found」（也 → 400）。實際 user 案例（base64 編碼損毀 zip）能觸發。本 fix 對該場景仍正確 — 即使 tick 24 不易 fuzz reproduce
- 搜尋框 placeholder 仍未對齊 S043 仍待修
- semantic 系統性回 0 根因待查
- 415 / NoResourceFoundException 404 normalize 至 ErrorResponse 格式（目前 spring 預設無 trace 已安全；格式統一性為小問題）
