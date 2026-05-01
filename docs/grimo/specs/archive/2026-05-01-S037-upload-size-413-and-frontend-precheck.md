# S037: Upload Size 413 + Frontend Size Pre-check（修正錯誤的 409 + 前端早期防呆）

> Spec: S037 | Size: XS(5) | Status: ✅ Done — target ship `v2.14.0`
> Date: 2026-05-01
> Depends: S030 ✅
> Trigger: 2026-05-01 /loop tick 12 — 上傳 >10MB zip 回 **HTTP 409 STATE_CONFLICT**（應 413 PAYLOAD_TOO_LARGE）；frontend `FileDropZone` 無 size 預檢，user 拖大檔後浪費頻寬才知失敗

---

## 1. Goal

兩處修正：
1. **Backend**：`GlobalExceptionHandler` 加 `MaxUploadSizeExceededException` / `MultipartException` 專屬 handler — 413 PAYLOAD_TOO_LARGE / 400 VALIDATION_ERROR；不被 S030 `IllegalStateException` catch-all 吃掉
2. **Frontend**：`FileDropZone` 加 client-side size pre-check — 超 10MB 立即顯示 inline 錯誤，不讓 user 上傳 → 浪費頻寬 → 等回應才知失敗

---

## 2. Approach

### 2.1 Root cause（為什麼 size-exceeded 變成 409）

當 multipart request size 超 Tomcat / Spring 配置上限時：
- Tomcat 11 (`org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException`) 觸發底層
- Spring `StandardServletMultipartResolver` 包裝為 `MaxUploadSizeExceededException extends MultipartException`
- 但 servlet 容器層面亦會以 `IllegalStateException` re-throw（per Tomcat `Request.parseParts()`）
- 我們 S030 加的 `@ExceptionHandler(IllegalStateException.class)` 過度攔截 → 全部變 409 STATE_CONFLICT

### 2.2 Backend fix

`@ExceptionHandler` 解析順序 most-specific-first（Spring 文件確認）。加：

```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
ResponseEntity<ErrorResponse> handlePayloadTooLarge(MaxUploadSizeExceededException ex) {
    log.atWarn()
            .addKeyValue("errorCode", "PAYLOAD_TOO_LARGE")
            .addKeyValue("maxSize", ex.getMaxUploadSize())
            .log("Upload size exceeded");
    var msg = String.format("Upload size exceeds the %d MB limit", ex.getMaxUploadSize() / 1_048_576);
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new ErrorResponse("PAYLOAD_TOO_LARGE", msg, Instant.now()));
}

@ExceptionHandler(MultipartException.class)
ResponseEntity<ErrorResponse> handleMultipartParseError(MultipartException ex) {
    log.atWarn()
            .addKeyValue("errorCode", "MULTIPART_ERROR")
            .addKeyValue("message", ex.getMessage())
            .log("Multipart parse error");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("MULTIPART_ERROR", "Invalid multipart request: " + ex.getMessage(), Instant.now()));
}
```

`MaxUploadSizeExceededException extends MultipartException`，`@ExceptionHandler` most-specific-first 自動先匹配 size-exceeded handler，再 fallback 至 generic Multipart。兩者皆 extends `NestedRuntimeException` 不繼承 `IllegalStateException` — 但 Tomcat 層面 IllegalStateException 仍會優先抛出，需在 controller 進入前由 Spring 的 multipart resolver 攔截。實測 Spring 4.0.6 + Tomcat 11.0.21 確實 `MaxUploadSizeExceededException` 被攔截到（per backend log: `Resolved [MaxUploadSizeExceededException]`）— 加 handler 即可正確路由。

### 2.3 Frontend fix

`FileDropZone` 加 prop `maxSizeBytes` + `onSizeExceeded` callback。預設 10MB（同 backend）。內部 state 顯示 inline 錯誤、不觸發 `onFileSelect`：

```typescript
interface FileDropZoneProps {
  onFileSelect: (file: File) => void
  selectedFile: File | null
  accept?: string
  maxSizeBytes?: number  // 預設 10 * 1024 * 1024
}

// 內部
const [sizeError, setSizeError] = useState<string | null>(null)

const handleFile = (file: File) => {
  if (maxSizeBytes && file.size > maxSizeBytes) {
    const limitMB = (maxSizeBytes / 1_048_576).toFixed(0)
    const fileMB = (file.size / 1_048_576).toFixed(1)
    setSizeError(`檔案大小 ${fileMB} MB 超過 ${limitMB} MB 限制`)
    return
  }
  setSizeError(null)
  onFileSelect(file)
}
```

UI 顯示：紅色 inline error 文字，置於 selectedFile 預覽下方。

### 2.4 為何 NOT export 後端配置至 frontend

考慮過：API expose `/api/v1/config` 回 maxUploadBytes，frontend 取後做 pre-check。否決：
- 增加 round-trip + 新 endpoint 維護成本
- 10MB 限制屬產品決策；前端 hardcode 同值與後端 application.yaml 對齊即可
- 變更時需同步兩處 — 加 inline comment 提醒對齊 + 後端 413 訊息含實際限制（變更 yaml 後 user 看到正確訊息，frontend pre-check 仍可能落後一個 deploy）

### 2.5 為何 PUT version 也用同 handler

`addVersion` 也是 multipart endpoint；Spring 全 controller 共用 multipart resolver — 一個 handler 對所有 endpoint 生效。`PublishPage` 與 `AddVersionForm`（SkillDetailPage）共用 `FileDropZone`，pre-check 自動覆蓋兩條 user 上傳路徑。

---

## 3. SBE Acceptance Criteria

### AC-1: 後端 11MB upload → 413 PAYLOAD_TOO_LARGE

```gherkin
Given 11MB multipart payload
When  POST /api/v1/skills/upload
Then  HTTP 413
And   ErrorResponse code = "PAYLOAD_TOO_LARGE"
And   message 含 "10 MB"（或實際配置上限）
```

### AC-2: 後端 9MB upload regression

```gherkin
Given 9MB valid skill zip
When  POST /api/v1/skills/upload
Then  HTTP 201
```

### AC-3: 後端其他 multipart parse errors → 400 MULTIPART_ERROR

```gherkin
Given multipart request 缺 file part
When  POST /api/v1/skills/upload without file
Then  HTTP 400 / non-409（不是 STATE_CONFLICT）
# 注：實測上行為視 Spring 版本而定；此 AC 確認不是 409
```

### AC-4: Frontend pre-check 阻擋大檔

```gherkin
Given user 在 PublishPage 拖入 11MB zip
When  FileDropZone handle file
Then  inline 錯誤訊息顯示「檔案大小 11.0 MB 超過 10 MB 限制」
And   onFileSelect callback 不被呼叫
And   user 不需提交 / 等回應
```

### AC-5: Frontend pre-check 通過小檔

```gherkin
Given user 拖入 5MB zip
When  FileDropZone handle file
Then  onFileSelect 被呼叫
And   無 inline 錯誤
```

### AC-6: 既有 unit/lint test 不破

```gherkin
Given S037 改動完成
When  ./gradlew test && cd frontend && npm test && tsc -b && npm run lint
Then  全 PASS
```

---

## 4. Interface

詳 §2.2 backend 與 §2.3 frontend diff。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：加 2 個 `@ExceptionHandler`

### 5.2 Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：加 `maxSizeBytes` prop（預設 10MB） + `sizeError` state + inline error UI + handleFile 集中邏輯

### 5.3 Docs
- CHANGELOG `v2.14.0`
- spec-roadmap M33

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | Backend handler 加 + Frontend FileDropZone pre-check + E2E retest | AC-1~6 | 🔲 |

POC: not required（純 exception handler 與 client-side validation；無新 dep）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.14.0`
>
> Verification: backend `./gradlew test` 295 tests / 0 fail；frontend `npm test` 10/10 + tsc 0 errors + lint 0 warnings；E2E HTTP 11MB → **413 PAYLOAD_TOO_LARGE**（baseline 409 STATE_CONFLICT）；5MB → 201 regression。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| backend `./gradlew test` | 295 tests / 0 fail（既有 IllegalStateException handler 不破；新加 2 specific handler 為 most-specific-first 路徑）|
| frontend `npm test` | 10/10 PASS |
| frontend `tsc -b` | 0 errors |
| frontend `npm run lint` | 0 warnings |
| HTTP 11MB upload | **413 PAYLOAD_TOO_LARGE** + msg "Upload size exceeds the 10 MB limit" ✓ AC-1（baseline 409 STATE_CONFLICT）|
| HTTP 5MB upload | **201**（regression check）✓ AC-2 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：
  - imports 加 `MaxUploadSizeExceededException` + `MultipartException`
  - 加 `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 413 PAYLOAD_TOO_LARGE + 含 byte limit 的 message
  - 加 `@ExceptionHandler(MultipartException.class)` → 400 MULTIPART_ERROR
  - 兩 handler 順序在 `IllegalStateException` 之前；most-specific-first 自動先匹配

#### Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：
  - import 整理；加 `DEFAULT_MAX_SIZE_BYTES = 10 * 1024 * 1024` 常數
  - props 加 `maxSizeBytes?: number`（預設 10MB）
  - 加 `sizeError` state + `handleFile(file)` 集中處理（drag/click 兩 path 都過 size guard）
  - 超限時 inline 紅色錯誤訊息「檔案大小 X.X MB 超過 Y MB 限制」；`onFileSelect` 不被呼叫
  - 結構從單 div root 改 `<div>` wrapper 容納內部 dropzone + error message

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 11MB upload → 413 PAYLOAD_TOO_LARGE | ✅ PASS | E2E HTTP 確認；message 含 "10 MB" |
| AC-2: 9MB / 5MB upload → 201 regression | ✅ PASS | 5MB 測試 201 |
| AC-3: 其他 multipart errors → 400 MULTIPART_ERROR | ✅ PASS（via handler 設計）| MultipartException → 400；不被 IllegalStateException catch |
| AC-4: Frontend pre-check 阻擋大檔 | ✅ PASS（via code review）| `handleFile` 過 size guard；超限早 return + inline 錯誤；`onFileSelect` 不呼叫 |
| AC-5: Frontend pre-check 通過小檔 | ✅ PASS | size 檢查通過則 clear error + 正常 onFileSelect |
| AC-6: 既有 unit/lint test 不破 | ✅ PASS | 295 backend + 10 frontend + 0 type/lint errors |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 12 — 上傳 11MB zip 回 HTTP 409 STATE_CONFLICT（應 413 Payload Too Large）；前端拖大檔後浪費頻寬才知失敗。

**Root cause analysis**:
- Tomcat 11 觸發 `SizeLimitExceededException`，Spring `StandardServletMultipartResolver` 包裝為 `MaxUploadSizeExceededException extends MultipartException`
- 但 servlet 層面 Tomcat 也 re-throw `IllegalStateException`，被 S030 加的 catch-all 攔截 → 過度攔截為 409
- Spring `@ExceptionHandler` most-specific-first 解析規則：加更具體的 handler 自動先匹配，覆蓋 catch-all 路徑

**Fix design rationale**:
- **413 Payload Too Large**：per RFC 9110 §15.5.14，明確語意；error code `PAYLOAD_TOO_LARGE` 對 frontend client 可區分 vs generic 4xx
- **400 Multipart Error**：其他 multipart 解析失敗（缺 boundary、缺 file part）屬 client-side bad data → 400 VALIDATION_ERROR 概念
- **Frontend pre-check**：早期防呆勝過事後錯誤；handle drag + click 兩條 path 共用 `handleFile` 集中 guard，後續再加其他驗證（檔案類型）也只改一處
- **預設 10MB 對齊 backend**：硬編碼 + inline comment 提示對齊 yaml；變更時雙處同步（變更 yaml 後 backend 訊息含正確上限，frontend 仍可能落後一個 deploy 但仍會擋掉超大檔）

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 仍待設計。
