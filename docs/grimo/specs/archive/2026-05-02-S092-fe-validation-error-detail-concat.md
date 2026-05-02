# S092 — FE i18n VALIDATION_ERROR / CONSTRAINT_VIOLATION field-level detail concat

> **Status**: shipped
> **Type**: polish (close tick 60 R18.3 tech-debt + tick 70/82 known polish candidate)
> **Estimate**: XS / 2 pts

## §1 Problem

Frontend i18n（`api-error-messages.ts`）對所有 `VALIDATION_ERROR` 都對應到 generic 模板「zip 套件驗證失敗，請確認格式正確。」，**不顯示具體 field 名**（如「Field 'name' fails regex」、「Missing required field: description」、「Field 'description' exceeds 1024 characters」）。User 看到錯誤後無法定位該改哪個欄位，必須開 DevTools 看 raw API response 才能知道。

Backend `SkillValidator` 與 `GlobalExceptionHandler` 已產出**具體 field-aware** error message（含欄位名 + actual value）— 但被 FE i18n template 攔住，user 看不到。

## §2 Approach

把 `ERROR_MESSAGES: Record<string, string>` 升級為 `ERROR_MESSAGE_BUILDER: Record<string, (backend?: string) => string>` — code 對應一個 builder function 而非靜態字串。對 field-level 錯誤（`VALIDATION_ERROR` / `CONSTRAINT_VIOLATION`）concat backend message 至繁中 prefix 後；其他 code 仍 fixed（DUPLICATE/STATE/etc 沒 actionable detail 可帶）。

格式設計：「驗證失敗：{backend message}」雙語並陳。Prefix 繁中讓 user 知道是「驗證類錯誤」(error category)；suffix 英文 detail 帶具體 field+value（精確定位）。

捨棄方案：
- (A) 改 backend message 為繁中：違反 `qa-strategy.md`「API 錯誤訊息: 英文（給前端轉譯用）」原則
- (B) 完全英文（捨繁中 prefix）：違反 CLAUDE.md「UI 語言: 繁體中文」
- (C) FE 重建 field 對應翻譯表：每個 validator message 都要 maintain 雙份，違 DRY 且容易漂移

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `VALIDATION_ERROR` + backend message `Field 'name' fails regex...` | UI 顯「驗證失敗：Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)」 |
| AC-1b | `VALIDATION_ERROR` + 空 message | fallback 「驗證失敗，請確認資料格式正確。」 |
| AC-2 | `CONSTRAINT_VIOLATION` + backend detail | UI 顯「資料驗證失敗：{detail}」 |
| AC-3 | `DUPLICATE_RESOURCE` | 仍 fixed 「此名稱已被使用，請換一個名稱。」（不洩漏 SQL detail） |
| AC-4 | 未知 code（如 `UNKNOWN_CODE`） | fallback 至 `err.message` |
| AC-5 | 非 ApiError 但 Error（如 Network） | 回 `err.message` |
| AC-6 | 非 Error（null / 字串） | 回 「未知錯誤」 |

## §4 Implementation

`frontend/src/lib/api-error-messages.ts`：

```ts
const ERROR_MESSAGE_BUILDER: Record<string, (backend?: string) => string> = {
  VALIDATION_ERROR: (m) =>
    m && m.trim().length > 0 ? `驗證失敗：${m}` : '驗證失敗，請確認資料格式正確。',
  CONSTRAINT_VIOLATION: (m) =>
    m && m.trim().length > 0 ? `資料驗證失敗：${m}` : '提交資料超過允許的長度或格式，請檢查後重試。',
  // ... 其他 code 為 () => '...' 形式
}

export function localizeApiError(err: unknown): string {
  if (ApiError.is(err) && err.code && ERROR_MESSAGE_BUILDER[err.code]) {
    return ERROR_MESSAGE_BUILDER[err.code](err.message)
  }
  if (err instanceof Error) return err.message
  return '未知錯誤'
}
```

PublishPage / SkillDetailPage 兩 caller **無需改動** — return 仍是 string，渲染管線不變（DESIGN.md `card-callout-danger` 仍正確顯示）。

## §5 Test plan

- 新增 `frontend/src/lib/api-error-messages.test.ts` — 7 個 vitest case (AC-1/1b/2/3/4/5/6)
- `npm test`（既有 11 → 18 PASS）
- `npm run build`（regression check：JS bundle 不超 360KB；既有為 351KB）
- E2E smoke：上傳 bad-name skill (regex fail) → 確認 backend message 形狀符合 i18n concat 假設

Backend audit 結論：`SkillValidator` errors 已含具體 field+value（如 `Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)`、`Field 'description' exceeds 1024 characters`、`Missing required field: name`、`Field 'allowed-tools' contains invalid token: XYZ`）；`GlobalExceptionHandler.handleValidationError` 透傳 `ex.getMessage()` 至 `ErrorResponse.message`。Backend 不需修改。

## §6 Verification

- `npm test` — 18/18 PASS（既有 11 + 新 7）
- `npm run build` — 200ms / 351KB JS（無 regression）
- E2E smoke — POST /skills/upload with `name: BAD-Name` zip → 400 + ErrorResponse `{error: "VALIDATION_ERROR", message: "SKILL.md validation failed: Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)"}` ✓

## §7 Result

- **Frontend tests**: 11 → 18 PASS / 0 fail
- **JS bundle**: 351KB（與 v2.66.0 相當；無 regression）
- **Backend**: 0 changes（audit 後確認 messages 已 field-aware）
- **Smoke**: 1 case (bad-name regex) → backend message 含具體 field+value ✓ → FE concat 後 user 可直接定位欄位
- **Tech debt cleared**: tick 60 R18.3 「i18n 訊息過於 generic」標記為 closed
- **Pre vs Post UX**:
  - Before: 「zip 套件驗證失敗，請確認格式正確。」
  - After: 「驗證失敗：SKILL.md validation failed: Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)」

ship as **v2.67.0** (M86)。
