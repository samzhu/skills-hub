# S098b3-2 — Backend 結構化 Findings Payload

**Status:** 📐 設計中
**Size:** S(6)
**Depends on:** S098b3 ✅ (UI shell already ships ErrRow; backend 目前只送 flat msg)
**Target version:** v4.13.0

---

## §1 Goal

S098b3 ship 了 `ValidationSection` + `ErrRow` UI shell，但 backend 仍送 flat `message` string（`"SKILL.md validation failed: name is required; description too short"`），前端只派生一個 error row。本 spec 讓 backend upload validation 回傳結構化 `findings` 陣列，使 `PublishFailedPage` 能逐項渲染多個 error / warning row 並附 hint。

**場景：** SKILL.md 有 3 個缺失欄位 → upload 400 → PublishFailedPage 顯示「3 errors・1 warning」，每 row 有 title + hint，讓開發者知道**分別要改什麼**。

**不是：** bundle_structure / risk_scan section 的結構化（非 sync upload path）；SKILL.md not found（不是 validation error，繼續用 flat）；GlobalExceptionHandler 其他 handler 改動。

---

## §2 Approach

### 2.1 Backend 新增型別

**`ValidationFinding` record**（`skill/validation/ValidationFinding.java`）：

```java
package io.github.samzhu.skillshub.skill.validation;

/** S098b3-2 — 一個結構化 validation finding，對應 PublishFailedPage ErrRow UI。 */
public record ValidationFinding(
        String section,   // "skill_md" (V1 only)
        String severity,  // "error" | "warning"
        String title,     // 具體錯誤訊息（原 errors list item）
        String hint       // 修正提示（nullable；V1 先留 null）
) {}
```

**`SkillValidationException`**（`skill/validation/SkillValidationException.java`）：

```java
package io.github.samzhu.skillshub.skill.validation;

import java.util.List;

/** 取代 IllegalArgumentException(SKILL.md validation)，攜帶結構化 findings。 */
public class SkillValidationException extends RuntimeException {
    private final List<ValidationFinding> findings;

    public SkillValidationException(String message, List<ValidationFinding> findings) {
        super(message);
        this.findings = List.copyOf(findings);
    }

    public List<ValidationFinding> findings() { return findings; }
}
```

### 2.2 ErrorResponse 擴展

`ErrorResponse` record 加 `findings` field（nullable，舊路徑送 null 保持 backward compat）：

```java
public record ErrorResponse(
        String error,
        String message,
        Instant timestamp,
        @Nullable List<ValidationFinding> findings  // S098b3-2；null = non-structured error
) {
    // backward compat factory（現有所有呼叫點用）
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now(), null);
    }
    // structured factory
    public static ErrorResponse withFindings(String error, String message, List<ValidationFinding> findings) {
        return new ErrorResponse(error, message, Instant.now(), findings);
    }
}
```

> **caller 遷移：** grep 所有 `new ErrorResponse(...)` → 改用 `ErrorResponse.of(...)` factory；構成不變，只是不再直接 call 3-arg record constructor（4th arg = null by factory）。

### 2.3 SkillCommandService 改動

`uploadSkill()` + `addVersion()` 兩個 validation fail 路徑：

```java
// BEFORE
throw new IllegalArgumentException("SKILL.md validation failed: " + String.join("; ", validation.errors()));

// AFTER
var findings = buildFindings(validation);
throw new SkillValidationException("SKILL.md validation failed", findings);
```

`buildFindings(ValidationResult)` private method：
```java
private List<ValidationFinding> buildFindings(ValidationResult r) {
    var list = new ArrayList<ValidationFinding>();
    for (var err : r.errors()) {
        list.add(new ValidationFinding("skill_md", "error", err, null));
    }
    for (var warn : r.warnings()) {
        list.add(new ValidationFinding("skill_md", "warning", warn, null));
    }
    return list;
}
```

### 2.4 GlobalExceptionHandler 新增 handler

```java
@ExceptionHandler(SkillValidationException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
ResponseEntity<ErrorResponse> handleSkillValidation(SkillValidationException ex) {
    log.atWarn()
       .addKeyValue("errorCode", "VALIDATION_ERROR")
       .addKeyValue("findingsCount", ex.findings().size())
       .log("SKILL.md validation failed");
    return ResponseEntity.badRequest()
           .body(ErrorResponse.withFindings("VALIDATION_ERROR", ex.getMessage(), ex.findings()));
}
```

### 2.5 前端型別 + ApiError 擴展

`types/skill.ts`（或新建 `types/api.ts`）加：
```typescript
export interface ValidationFinding {
  section: 'skill_md'           // V1 only; future: 'bundle_structure' | 'risk_scan'
  severity: 'error' | 'warning'
  title: string
  hint: string | null
}
```

`api/client.ts` 的 `ApiError` 加 optional `findings: ValidationFinding[]`：
```typescript
class ApiError extends Error {
  // ...existing fields...
  findings?: ValidationFinding[]

  constructor(status: number, message: string, code?: string, findings?: ValidationFinding[]) {
    super(message)
    this.findings = findings
  }
}
```

`apiFetch` error body parse 加 findings：
```typescript
const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
throw new ApiError(res.status, message, b.error, b.findings)
```

### 2.6 PublishPage 傳遞 findings

```typescript
// BEFORE (line ~109)
const msg = encodeURIComponent(localizeApiError(err))
navigate(`/publish/failed?state=A&msg=${msg}`)

// AFTER
const findings = ApiError.is(err) ? err.findings : undefined
navigate('/publish/failed', {
  state: { state: 'A', findings, msg: localizeApiError(err) }
})
```

### 2.7 PublishFailedPage 讀 findings

```typescript
// AFTER: 優先讀 React Router state findings（有結構）；fallback 讀 URL msg（backward compat）
import { useLocation, useSearchParams } from 'react-router'

const location = useLocation()
const [params] = useSearchParams()
const routeState = location.state as { findings?: ValidationFinding[]; msg?: string; state?: string } | null

// findings 優先；fallback → 包 flat msg 為單一 ErrRow
const findings: ErrRow[] = routeState?.findings?.map(f => ({
  severity: f.severity,
  title: f.title,
  hint: f.hint ?? undefined,
})) ?? (msg ? [{ severity: 'error', title: msg }] : [])
```

### 2.8 Trim / Defer

- **Defer：** `bundle_structure` + `risk_scan` section（不在 sync upload path）
- **Defer：** per-error hint text（語意化提示；V1 留 null，讓 title 清楚即可）
- **Defer：** `VersionAddPage` 的 addVersion validation（同樣路徑，follow-up polish）
- **Core（本 tick）：** backend ValidationFinding / SkillValidationException + ErrorResponse factory + GlobalExceptionHandler + frontend ApiError findings + PublishPage/FailedPage wiring

---

## §3 Acceptance Criteria

**AC-1 — 結構化 findings 出現在 API response**
```
Given: 上傳 SKILL.md 缺少 name + description 兩個欄位
When:  POST /api/v1/skills/upload
Then:  400 + body.error = "VALIDATION_ERROR"
       body.findings = [
         {section:"skill_md",severity:"error",title:"name is required",hint:null},
         {section:"skill_md",severity:"error",title:"description is required",hint:null}
       ]
       body.findings.length = 2
```

**AC-2 — PublishFailedPage 顯示多個 ErrRow**
```
Given: AC-1 的 400 response
When:  PublishPage onError navigate → /publish/failed
Then:  PublishFailedPage 顯示「2 errors・0 warnings」
       顯示 2 個 ErrRow（name is required / description is required）
       不再顯示 flat concatenated string
```

**AC-3 — ValidationResult warnings 對應 warning ErrRow**
```
Given: SKILL.md 驗證通過（errors 空）但有 1 個 warning
When:  POST /api/v1/skills/upload（仍會 success，warnings 不阻斷）
Then:  200 response（upload 成功）
       — warnings 不 block upload，只在 PublishValidatePage 有需要時展示（V1 defer）
```
> Note: AC-3 確認 warnings 不阻斷 upload，findings 只在 validation fail 時送出（errors 非空時才進 SkillValidationException 路徑）

**AC-4 — 舊 URL msg= fallback 不 break**
```
Given: 直接訪問 /publish/failed?state=A&msg=SKILL.md+validation+failed
When:  瀏覽 PublishFailedPage
Then:  仍顯示 1 個 ErrRow（fallback 路徑）；not blank；no JS error
```

**AC-5 — 其他 IllegalArgumentException 路徑不受影響**
```
Given: SKILL.md not found in zip（IllegalArgumentException 路徑）
When:  POST /api/v1/skills/upload（zip 不含 SKILL.md）
Then:  400 + body.error = "VALIDATION_ERROR"
       body.findings = null（不是 SkillValidationException）
       frontend fallback msg 正常顯示
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/.../skill/validation/ValidationFinding.java` | new — `section / severity / title / hint` record |
| `backend/.../skill/validation/SkillValidationException.java` | new — carries `List<ValidationFinding>` |
| `backend/.../shared/api/ErrorResponse.java` | modify — 加 `findings` field + `of()` / `withFindings()` factory |
| `backend/.../shared/api/GlobalExceptionHandler.java` | modify — 加 `handleSkillValidation(SkillValidationException)` handler；把現有 `new ErrorResponse(...)` → `ErrorResponse.of(...)` |
| `backend/.../skill/command/SkillCommandService.java` | modify — `uploadSkill` + `addVersion` validation fail → throw `SkillValidationException`；加 `buildFindings()` private method |
| `frontend/src/types/skill.ts` (or `api.ts`) | modify — 加 `ValidationFinding` interface |
| `frontend/src/api/client.ts` | modify — `ApiError` 加 `findings?` field；`apiFetch` parse `body.findings` |
| `frontend/src/pages/PublishPage.tsx` | modify — onError navigate 改用 React Router state 傳 findings |
| `frontend/src/pages/PublishFailedPage.tsx` | modify — `useLocation()` 讀 route state findings；fallback URL msg |
| `frontend/src/pages/PublishFailedPage.test.tsx` | modify — 加 AC-2/AC-4 finding-render test |
| `backend/src/test/.../SkillCommandControllerTest.java` | modify — AC-1 integration test：upload invalid SKILL.md → verify findings array |

---

## §5 Test Plan

- **AC-1 integration（backend）**：`@SpringBootTest` 上傳缺 name + description 的 SKILL.md → verify `body.findings` length=2, severity/title
- **AC-2 unit（frontend）**：`PublishFailedPage.test.tsx` — mock `useLocation` 帶 findings state → verify 2 ErrRow render
- **AC-4 regression（frontend）**：`PublishFailedPage.test.tsx` — no location state, URL `?msg=foo` → verify fallback 1 ErrRow
- **AC-5 regression（backend）**：upload zip 無 SKILL.md → verify `findings: null` in response body
- **Regression**：`cd frontend && npm test -- --reporter verbose` + `./gradlew compileJava`
