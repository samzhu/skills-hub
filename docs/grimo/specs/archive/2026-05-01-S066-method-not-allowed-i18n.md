# S066: METHOD_NOT_ALLOWED i18n Coverage

> Spec: S066 | Size: XS(5) | Status: ✅ Done — target ship `v2.44.0`
> Trigger: 2026-05-01 /loop tick 39 — backend / frontend i18n map 比對發現 `METHOD_NOT_ALLOWED`（S045 ship）漏譯。User 觸 405 會看英文 fallback message。

---

## 1. Goal

`api-error-messages.ts` 加 `METHOD_NOT_ALLOWED: '此操作的請求方法不正確，請重新整理頁面後再試。'`

---

## 7. Implementation Results — ✅ Done

### Verification
- `npm test` — 10 / 0 fail
- Backend ↔ Frontend i18n 12/12 codes 全覆蓋

### Files Changed (1)
- `frontend/src/lib/api-error-messages.ts`：加 `METHOD_NOT_ALLOWED` entry

### Coverage Audit
| Backend Code | Frontend i18n |
|---|---|
| CONCURRENT_MODIFICATION | ✓ |
| CONSTRAINT_VIOLATION | ✓ |
| DUPLICATE_RESOURCE | ✓ |
| INVALID_REQUEST_BODY | ✓ |
| METHOD_NOT_ALLOWED | ✓（新增）|
| MULTIPART_ERROR | ✓ |
| NOT_FOUND | ✓ |
| PAYLOAD_TOO_LARGE | ✓ |
| SKILL_SUSPENDED | ✓ |
| STATE_CONFLICT | ✓ |
| VALIDATION_ERROR | ✓ |
| VERSION_EXISTS | ✓ |
