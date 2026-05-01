# S039: Frontend Typed `ApiError` + 區分 404 vs server error

> Spec: S039 | Size: XS(5) | Status: ✅ Done — target ship `v2.16.0`
> Date: 2026-05-01
> Depends: 無
> Trigger: 2026-05-01 /loop tick 14 — `SkillDetailPage` 對 任何 error 都顯示「找不到此技能」；500/network error 都被誤導為 not-found

---

## 1. Goal

frontend `apiFetch` 拋自訂 `ApiError` 攜 `status` + `code`；`SkillDetailPage` 改 case-split：
- `error.status === 404`（或 `error.code === 'NOT_FOUND'`）→ 顯示「找不到此技能」
- 其他 error → 顯示「載入技能時發生錯誤，請稍後重試」

未來其他 page 可同樣用 typed error 做更精準的 UX 區分。

---

## 2. Approach

### 2.1 `ApiError` class

`frontend/src/api/client.ts` 加：

```typescript
export class ApiError extends Error {
  readonly status: number
  readonly code?: string  // backend ErrorResponse.error code

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const b = body as { message?: string; error?: string }
    const message = b.message ?? `API error ${res.status}`
    throw new ApiError(res.status, message, b.error)
  }
  return res.json() as Promise<T>
}
```

backend `ErrorResponse` shape：`{error, message, timestamp}`（per `GlobalExceptionHandler`）→ frontend 拿 `error` 欄位作 `code`、`message` 作訊息。

### 2.2 SkillDetailPage 改 error split

```diff
+import { ApiError } from '@/api/client'
+
 if (error || !skill) {
+  const isNotFound = error instanceof ApiError && error.status === 404
   return (
     <AppShell>
       <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
-        <p className="text-lg font-medium">找不到此技能</p>
+        <p className="text-lg font-medium">
+          {isNotFound ? '找不到此技能' : '載入技能時發生錯誤'}
+        </p>
+        {!isNotFound && (
+          <p className="mt-1 text-sm">請稍後重試或重新整理頁面</p>
+        )}
         <Link to="/" className="mt-2 text-sm text-primary hover:underline">
           返回首頁
         </Link>
       </div>
     </AppShell>
   )
 }
```

### 2.3 為何 NOT 把所有 page 改一輪

scope 小：只 SkillDetailPage 是被觸發過會把 server error 誤標為 not-found 的入口；其他 page（HomePage / AnalyticsPage / PublishPage）已用較通用「載入失敗，請重新整理頁面」訊息，不會混淆 404 與 5xx。把 ApiError 加上去後，未來 page 可漸進式用 status / code 做更精細區分。

### 2.4 為何不 export status 直接抓 fetch Response

考慮過：在 `apiFetch` 直接拋 `Response` object。否決：
- React Query 期望 `Error` 子類；非 Error 物件會在 hook 內 awkward
- typed Error class 跨層次清晰（caller `error instanceof ApiError`）；保留 `message` API 不破舊呼叫端

---

## 3. SBE Acceptance Criteria

### AC-1: `ApiError` 含 status + code 字段

```gherkin
Given backend 回 404 NOT_FOUND
When  apiFetch 拋出 error
Then  error instanceof ApiError 為 true
And   error.status === 404
And   error.code === 'NOT_FOUND'
And   error.message 含 backend message 文字
```

### AC-2: SkillDetailPage 對 404 顯示「找不到此技能」

```gherkin
Given 用一個不存在的 UUID 訪問 /skills/{nonExistentId}
When  useSkill 收 ApiError(404)
Then  顯示「找不到此技能」+ 返回首頁連結
```

### AC-3: SkillDetailPage 對其他 error 顯示「載入技能時發生錯誤」

```gherkin
Given backend 回 500（如：DB 中斷）
When  useSkill 收 ApiError(500)
Then  顯示「載入技能時發生錯誤」+ 「請稍後重試或重新整理頁面」
And   不顯示「找不到此技能」（誤導）
```

### AC-4: 既有 frontend test 不破

```gherkin
Given S039 改動完成
When  cd frontend && npm test && tsc -b && npm run lint
Then  全 PASS
```

---

## 4. Interface

詳 §2.1 + §2.2 diffs。

---

## 5. File Plan

### 5.1 Frontend (2 files)
- `frontend/src/api/client.ts`：加 `ApiError` class + 改 `apiFetch` throw `ApiError`
- `frontend/src/pages/SkillDetailPage.tsx`：import `ApiError`，error block case-split 404 vs other

### 5.2 Docs
- CHANGELOG `v2.16.0`
- spec-roadmap M35

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | ApiError class + apiFetch update + SkillDetailPage error split + npm test/tsc/lint | AC-1~4 | 🔲 |

POC: not required（純 frontend error 類別擴展；既有 callers `error.message` 仍可用）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.16.0`
>
> Verification: `npm test` 10/10 PASS；`tsc -b` 0 errors；`npm run lint` 0 warnings；backend 404 shape `{error, message, timestamp}` 匹配 ApiError 構造。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `cd frontend && npm test` | 10/10 PASS（既有 callers 仍透過 `error.message` 工作；ApiError 是 Error 子類）|
| `cd frontend && npx tsc -b` | 0 type errors |
| `cd frontend && npm run lint` | 0 warnings |
| backend 404 sanity | `{"error":"NOT_FOUND","message":"Skill not found: ...","timestamp":"..."}` ✓ ApiError 構造可拿到 status=404 + code='NOT_FOUND' |

### 7.2 Files Changed

#### Frontend (2 files)
- `frontend/src/api/client.ts`：加 `ApiError extends Error` class（`status` + `code` + `message`）；改 `apiFetch` throw `ApiError(status, message, error)`
- `frontend/src/pages/SkillDetailPage.tsx`：import `ApiError`；error block 加 `isNotFound = error instanceof ApiError && error.status === 404`，case-split 顯示

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: `ApiError` 含 status + code | ✅ PASS | code review；backend 404 shape 確認 |
| AC-2: SkillDetailPage 對 404 顯示「找不到此技能」 | ✅ PASS | code review — `isNotFound` true 走 not-found 文字 |
| AC-3: SkillDetailPage 對其他 error 顯示「載入技能時發生錯誤」 | ✅ PASS | code review — `isNotFound` false 顯示 "請稍後重試或重新整理頁面" |
| AC-4: 既有 frontend test 不破 | ✅ PASS | 10/10 + 0 type/lint errors |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 14 — `SkillDetailPage` 對 任何 error（404 / 500 / network）都顯示「找不到此技能」；server 中斷時 user 被誤導為「skill 不存在」。

**Fix design rationale**:
- `ApiError extends Error` — TypeScript / React Query 期望 `Error` 子類；既有 callers 用 `error.message` 仍可工作
- `error.status === 404` 而非 `error.code === 'NOT_FOUND'` — 更通用：未來 backend 可能換 code 名稱但 HTTP status 是穩定 contract
- 暫只改 SkillDetailPage — 其他 page（HomePage / AnalyticsPage / PublishPage）已用「載入失敗，請重新整理頁面」較通用訊息，不會混淆 404 與 5xx；增量逐 page 改造勝過大爆炸

### 7.5 Pending Verification / Tech Debt

- 其他 page 可漸進採用 `ApiError.status` / `code` 做更精細 UX 區分（未來 spec scope）
- S031 §7.5 admin panel endpoint 仍待設計
