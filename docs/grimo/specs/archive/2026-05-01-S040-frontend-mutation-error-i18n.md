# S040: Frontend Mutation Error i18n + Multipart 也用 ApiError

> Spec: S040 | Size: XS(5) | Status: ✅ Done — target ship `v2.17.0`
> Depends: S039 ✅
> Trigger: 2026-05-01 /loop tick 15 — `PublishPage` / `AddVersionForm` 直接顯示後端英文訊息「Upload size exceeds the 10 MB limit」/「SKILL.md not found in zip」於繁中 UI；違反 CLAUDE.md「UI 語言: 繁體中文」原則。同時 `uploadSkill` / `addVersion` mutations 因 FormData boundary 繞過 apiFetch，拋 plain Error 而非 ApiError，無 code 可對應翻譯。

---

## 1. Goal

兩處改：
1. **`uploadSkill` / `addVersion` mutations 也拋 `ApiError`**：解析後端 ErrorResponse 的 `error` code，與 `apiFetch` 行為對齊
2. **`PublishPage` / `AddVersionForm` 加 i18n mapping**：常見 error code（PAYLOAD_TOO_LARGE / VALIDATION_ERROR / STATE_CONFLICT / VERSION_EXISTS / MULTIPART_ERROR）對應繁中訊息；未知 code fallback 至原 message

---

## 2. Approach

### 2.1 Multipart mutations 拋 `ApiError`

`frontend/src/api/skills.ts` 的 `uploadSkill` 與 `addVersion`：

```diff
+import { ApiError } from './client'
+
 export async function uploadSkill(...): Promise<{ id: string }> {
   ...
   const res = await fetch('/api/v1/skills/upload', { method: 'POST', body: form })
   if (!res.ok) {
     const body = await res.json().catch(() => ({}))
-    throw new Error((body as { message?: string }).message ?? `Upload failed: ${res.status}`)
+    const b = body as { message?: string; error?: string }
+    throw new ApiError(res.status, b.message ?? `Upload failed: ${res.status}`, b.error)
   }
   return res.json() as Promise<{ id: string }>
 }
```

同 `addVersion`。

### 2.2 i18n message map

新增 `frontend/src/lib/api-error-messages.ts` 集中 error code → 繁中訊息 mapping：

```typescript
import { ApiError } from '@/api/client'

/** 常見 backend error code 對繁中訊息 — 不認識的 code fallback 至 ApiError.message。 */
const ERROR_MESSAGES: Record<string, string> = {
  PAYLOAD_TOO_LARGE: '檔案過大，請選擇 10 MB 以內的 zip 套件。',
  VALIDATION_ERROR: 'zip 套件驗證失敗，請確認格式正確。',
  MULTIPART_ERROR: '上傳請求格式不正確，請重新整理頁面後重試。',
  VERSION_EXISTS: '此版本號已存在，請改用其他版本號。',
  STATE_CONFLICT: '操作與目前狀態衝突，請重新整理後重試。',
  CONCURRENT_MODIFICATION: '資源被其他請求同時修改，請重試。',
  NOT_FOUND: '找不到指定的資源。',
  SKILL_SUSPENDED: '此技能已被停用，無法操作。',
}

export function localizeApiError(err: unknown): string {
  if (err instanceof ApiError && err.code && ERROR_MESSAGES[err.code]) {
    // 已知 code：用繁中模板（不附 backend message — 模板已含必要資訊；對 PAYLOAD_TOO_LARGE
    // 此類含具體限制的 case 已內嵌「10 MB」於模板）
    return ERROR_MESSAGES[err.code]
  }
  if (err instanceof Error) return err.message
  return '未知錯誤'
}
```

### 2.3 PublishPage / AddVersionForm 採用

```diff
+import { localizeApiError } from '@/lib/api-error-messages'
+
 {mutation.isError && (
   <div className="mt-4 rounded-md bg-red-50 p-4 text-red-800">
     <p className="font-medium">發佈失敗</p>
-    <p className="text-sm">{mutation.error.message}</p>
+    <p className="text-sm">{localizeApiError(mutation.error)}</p>
   </div>
 )}
```

同 `AddVersionForm`。

### 2.4 為何 NOT 建立完整 i18n 系統（i18next 等）

scope 小：當前只 ~10 個 error code 需中譯，i18n 框架 overkill；handcraft Record map 對齊 S028 STATUS_LABEL / S036 RISK_DESCRIPTION 既有模式。未來 UI 國際化擴大時可導入框架，現在 simplicity 勝。

### 2.5 為何 PAYLOAD_TOO_LARGE 模板硬編碼 10 MB

backend `MaxUploadSizeExceededException.getMaxUploadSize()` 回 -1（per S037 §7.4 觀察），訊息含 fallback 的 "10 MB"；frontend 模板亦硬編碼 10 MB 對齊。變更 yaml 上限時需同步三處（backend yaml / S037 fallback / S040 frontend 模板）— 加 inline 註解提醒。

---

## 3. SBE Acceptance Criteria

### AC-1: uploadSkill 失敗時拋 ApiError 含 status + code

```gherkin
Given 上傳 11MB zip
When  uploadSkill 拋出 error
Then  error instanceof ApiError 為 true
And   error.status === 413
And   error.code === 'PAYLOAD_TOO_LARGE'
```

### AC-2: addVersion 失敗時同樣拋 ApiError

```gherkin
Given PUT version with mismatched name zip
When  addVersion 拋出 error
Then  error instanceof ApiError 為 true
And   error.status === 400
And   error.code === 'VALIDATION_ERROR'
```

### AC-3: PublishPage 顯示繁中訊息

```gherkin
Given mutation error 為 ApiError(413, "Upload size exceeds the 10 MB limit", "PAYLOAD_TOO_LARGE")
When  render PublishPage
Then  顯示「檔案過大，請選擇 10 MB 以內的 zip 套件。」
And   不顯示英文「Upload size exceeds...」
```

### AC-4: AddVersionForm 同樣繁中翻譯

```gherkin
Given mutation error 為 ApiError(409, "...", "STATE_CONFLICT")
When  render AddVersionForm
Then  顯示「操作與目前狀態衝突，請重新整理後重試。」
```

### AC-5: 未知 code fallback 至 message

```gherkin
Given error 為 ApiError(500, "Internal error", "UNKNOWN_CODE")
When  localizeApiError(err)
Then  return "Internal error"

Given error 為 plain Error("network failed")
When  localizeApiError(err)
Then  return "network failed"
```

### AC-6: 既有 frontend test 不破

```gherkin
Given S040 改動完成
When  cd frontend && npm test && tsc -b && npm run lint
Then  全 PASS
```

---

## 4. Interface

詳 §2.1, §2.2, §2.3。

---

## 5. File Plan

### 5.1 Frontend (3 files)
- `frontend/src/api/skills.ts`：`uploadSkill` / `addVersion` 改 throw `ApiError`
- `frontend/src/lib/api-error-messages.ts`（new）：i18n Record map + `localizeApiError(err)` helper
- `frontend/src/pages/PublishPage.tsx`、`SkillDetailPage.tsx`：error 顯示改用 `localizeApiError`

### 5.2 Docs
- CHANGELOG `v2.17.0`
- spec-roadmap M36

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | api/skills.ts ApiError + i18n helper + PublishPage/AddVersionForm 採用 + tsc/lint/test | AC-1~6 | 🔲 |

POC: not required（純 frontend 翻譯與 typed error；既有 ApiError 已在 client.ts）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.17.0`
>
> Verification: `npm test` 10/10 PASS；`tsc -b` 0 errors；`npm run lint` 0 warnings；backend 三類錯誤 shape（PAYLOAD_TOO_LARGE / VALIDATION_ERROR / NOT_FOUND）E2E 確認對齊 i18n map。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `cd frontend && npm test` | 10/10 PASS |
| `cd frontend && npx tsc -b` | 0 type errors |
| `cd frontend && npm run lint` | 0 warnings |
| backend 413 sanity | `{"error":"PAYLOAD_TOO_LARGE","message":"Upload size exceeds the 10 MB limit",...}` ✓ |
| backend 400 sanity | `{"error":"VALIDATION_ERROR","message":"SKILL.md not found in zip",...}` ✓ |
| backend 404 sanity | `{"error":"NOT_FOUND","message":"Skill not found: ...",...}` ✓ |

### 7.2 Files Changed

#### Frontend (4 files)
- `frontend/src/api/skills.ts`：import `ApiError`；`uploadSkill` 與 `addVersion` 兩處 `throw new Error(...)` 改 `throw new ApiError(status, message, code)`
- `frontend/src/lib/api-error-messages.ts`（new）：`ERROR_MESSAGES: Record<string, string>` 8 個 code 對繁中模板 + `localizeApiError(err: unknown): string` helper
- `frontend/src/pages/PublishPage.tsx`：import `localizeApiError`；error block `mutation.error.message` → `localizeApiError(mutation.error)`
- `frontend/src/pages/SkillDetailPage.tsx`：同上 — `AddVersionForm` mutation error display 改用 `localizeApiError`

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: uploadSkill 拋 ApiError 含 status + code | ✅ PASS | code review；backend shape 確認 |
| AC-2: addVersion 同樣拋 ApiError | ✅ PASS | 兩 mutations 改動對稱 |
| AC-3: PublishPage 顯示繁中訊息 | ✅ PASS | code review — `localizeApiError` 翻譯 |
| AC-4: AddVersionForm 同樣繁中翻譯 | ✅ PASS | 同上 |
| AC-5: 未知 code fallback 至 message | ✅ PASS | `localizeApiError` 邏輯：未知 code 走 `err.message` 路徑；非 Error 回「未知錯誤」 |
| AC-6: 既有 frontend test 不破 | ✅ PASS | 10/10 + 0 type/lint errors |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 15 — `PublishPage` / `AddVersionForm` 直接顯示後端英文訊息「Upload size exceeds the 10 MB limit」/「SKILL.md not found in zip」於繁中 UI；違反 CLAUDE.md「UI 語言: 繁體中文」。同時 `uploadSkill` / `addVersion` 因 FormData boundary 繞過 `apiFetch`，拋 plain Error 而非 ApiError，無 code 可對應翻譯。

**Fix design rationale**:
- 統一 mutation error 都拋 `ApiError`（apiFetch + multipart fetch 對齊）；前端只需依 ApiError contract 處理錯誤，行為一致
- 集中 i18n Record map 於 `lib/api-error-messages.ts` — mirror S028 STATUS_LABEL / S036 RISK_DESCRIPTION / S033 後 author propagation 的 Record-based pattern
- 不引入 i18n 框架（i18next）— 8 個 code 規模太小，handcraft Record 維護成本更低；UI 大規模國際化時可導入框架
- 模板硬編碼 10 MB — 與 backend `application.yaml` 上限同步；變更時三處（yaml / S037 fallback / S040 模板）一起改，加 inline 註解提醒

### 7.5 Pending Verification / Tech Debt

- HomePage / AnalyticsPage list/query errors 仍直接顯示「載入失敗，請重新整理頁面」通用訊息 — 可漸進採用 `localizeApiError` 提供更精細 UX
- S031 §7.5 admin panel endpoint 仍待設計
