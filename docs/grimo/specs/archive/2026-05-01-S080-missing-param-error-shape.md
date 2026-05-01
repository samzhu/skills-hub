# S080 — `MissingServletRequestParameterException` 統一 ErrorResponse 格式

> **Status**: in-flight
> **Bug ledger**: AM (loop e2e tick 71 Round 27)
> **Estimate**: XS / 2 pts

## §1 Problem

`POST /api/v1/skills/upload` 缺 `version` 參數時，Spring 預設 error handler 直接回應，**繞過** GlobalExceptionHandler 的標準 ErrorResponse 格式：

```json
// Bug AM — 預設 Spring shape:
{
  "timestamp": "2026-05-01T...",
  "status": 400,
  "error": "Bad Request",
  "message": "Required parameter 'version' is not present.",
  "path": "/api/v1/skills/upload"
}
```

對比我們慣用的標準 shape（per `ErrorResponse` record）:
```json
{
  "error": "VALIDATION_ERROR",
  "message": "...",
  "timestamp": "2026-05-01T..."
}
```

差異：
- `error` field 內容不同：「Bad Request」(HTTP reason phrase) vs `VALIDATION_ERROR` (semantic code)
- 多 `status` / `path` 欄位
- 欄位順序不同

**Frontend i18n 影響**：S066 / S041 frontend i18n 用 `error` code → localized message。「Bad Request」不在 12 個 backend code 對應表中 → silently fall through，user 看到 raw EN 訊息或泛用 fallback。

## §2 Root Cause

GlobalExceptionHandler 沒處理 Spring binding-time 例外（`MissingServletRequestParameterException` / `MissingServletRequestPartException` / `ServletRequestBindingException` 系列）。Spring 預設 handler 在 GlobalExceptionHandler 之前 fall through。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | POST `/skills/upload` 缺 `version` form param | `{error: "VALIDATION_ERROR", message: "Required parameter 'version' is not present", timestamp: ...}` 400 |
| AC-2 | POST `/skills/upload` 缺 `file` multipart part | 同 shape，message 指 "file" |
| AC-3 | 既有 OK path 不影響 | upload happy path 仍 201 |
| AC-4 | 既有 4 種已 handle 的 4xx exceptions（NotFound / Validation / StateConflict / SkillSuspended）shape 不變 | regression check |

## §4 Fix

加 handler in `GlobalExceptionHandler`：

```java
@ExceptionHandler({
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class
})
ResponseEntity<ErrorResponse> handleMissingParam(Exception ex) {
    log.atWarn()
            .addKeyValue("errorCode", "VALIDATION_ERROR")
            .addKeyValue("message", ex.getMessage())
            .log("Missing required parameter");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), Instant.now()));
}
```

## §5 Test plan

`GlobalExceptionHandlerTest` 加 1 test：mock controller throw `MissingServletRequestParameterException` → assert `error == "VALIDATION_ERROR"`。

Smoke：curl POST `/skills/upload` 缺 version → JSON keys = ['error','message','timestamp']（不含 status/path）✓

## §6 Verification

- `./gradlew test` PASS
- Smoke 3 paths（缺 version / 缺 author / 缺 file）回標準 shape

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 regression）
- Smoke：3 paths 全回標準 shape
  - 缺 version → `{error: "VALIDATION_ERROR", message: "Required request parameter 'version'...", timestamp}` ✓
  - 缺 author → 同 shape ✓
  - 缺 file (multipart part) → `{error: "VALIDATION_ERROR", message: "Required part 'file'...", timestamp}` ✓
- 既有 happy path / 已 handle 的 4xx 不變
- ship v2.57.0 (M76)
