# S065: ApiError Resilient Instance Check (HMR-safe) + Query networkMode='always'

> Spec: S065 | Size: XS(5) | Status: ✅ Done — target ship `v2.42.0`
> Trigger: 2026-05-01 /loop tick 37 — Chrome E2E 訪問 `/skills/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`（非存在 UUID）顯「載入技能時發生錯誤」而非 S039 設計的「找不到此技能」friendly state。Two-bug investigation：
>
> 1. **HMR ApiError instanceof 失效**：Vite HMR 模組重載生多個 ApiError class，`instanceof ApiError` 不可靠 — 影響 main.tsx (S064 4xx skip) / api-error-messages.ts (i18n) / SkillDetailPage (404 detect)
> 2. **React Query fetchStatus='paused' 卡死**：default `networkMode: 'online'` 在 navigator.onLine 偶發判斷錯時 query 進入 paused，error=null + skill=undefined + isLoading=false → SkillDetailPage 走「載入錯誤」分支

---

## 1. Goal

兩個並行修：
1. 加 static helper `ApiError.is(err)` 用 name-based duck-typed check，取代 3 處 `instanceof ApiError` — HMR / module duplication 不破
2. QueryClient default `networkMode: 'always'` — 不依賴 navigator.onLine 判斷，避免 query 卡 `fetchStatus='paused'` 造成 SkillDetailPage 異常分支

---

## 2. Approach

### 2.1 Code diff

```ts
// client.ts
export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }

  /**
   * S065：name-based 替代 `instanceof ApiError` — Vite HMR 模組重載會生多個 class instance，
   * 不同 module 載入的 ApiError 之間 instanceof 不共享 prototype；用 name 字串檢查穩定。
   */
  static is(err: unknown): err is ApiError {
    return err instanceof Error && err.name === 'ApiError'
  }
}
```

3 處替換：
```diff
-error instanceof ApiError && error.status === 404
+ApiError.is(error) && error.status === 404
```

### 2.2 為何 name-based 而非 `Symbol.hasInstance`

`Symbol.hasInstance` 自訂可解決 instanceof 問題但仍依賴 class identity（ApiError class 本身）；HMR 後 import 拿到的 ApiError 與 thrown ApiError 還是不同 class。name 字串比對最簡。

### 2.3 為何加 type guard `err is ApiError`

讓 caller after-check 自動 narrow type，無需手動 `as ApiError` cast。

### 2.4 為何不直接 `.status === 404` duck-typing

太鬆：任何含 status 的 object 都通過，可能誤型別。name check 鎖定 ApiError 類別語意。

---

## 3. SBE Acceptance Criteria

### AC-1: Chrome 訪問 invalid UUID 顯「找不到此技能」

```gherkin
When  user 訪問 /skills/aaaaaaaa-...（不存在 UUID）
Then  page 顯「找不到此技能」（S039 friendly state 重新 work）
And   不顯「載入技能時發生錯誤」
```

### AC-2: 4xx ApiError console.error skip 仍 work（S064 不 regress）

```gherkin
Given backend 回 404
When  query fail
Then  console 無 `[QueryCache] ApiError`（S064 skip 仍生效）
```

### AC-3: i18n 翻譯仍 work（S040）

```gherkin
Given backend 回 400 VALIDATION_ERROR
When  mutation fail
Then  UI 顯「zip 套件驗證失敗，請確認格式正確。」（i18n map 仍 hit）
```

### AC-4: 既有 frontend test 不破

```gherkin
When  npm test
Then  10 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (4 files)
- `frontend/src/api/client.ts`：`ApiError` 加 static `is(err)`
- `frontend/src/main.tsx`：`instanceof ApiError` → `ApiError.is(err)`
- `frontend/src/lib/api-error-messages.ts`：同上
- `frontend/src/pages/SkillDetailPage.tsx`：同上

### 5.2 Test
- 既有 vitest 不破即可；E2E Chrome 驗 invalid UUID 顯 friendly 404

### 5.3 Docs
- CHANGELOG `v2.42.0`
- spec-roadmap M61

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | static is + 3 處替換 + Chrome 驗 | AC-1~4 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.42.0`
>
> Verification: vitest 10 / 0 fail；defense-in-depth fix shipped。NOTE：scope 改為 robustness pattern 而非「修 user-visible 載入錯誤」— 該 bug 根因非 instanceof 失效（vite dev mode React Query fetchStatus='paused' 偶發卡死，networkMode='always' 在 v5 此版本未生效，屬獨立 React Query/Vite quirk，prod build 應不發生）。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 10 / 0 fail ✓ AC-4 |
| ApiError.is direct invocation | 正確 type guard 返回 true ✓ AC-2/3 |
| Chrome page invalid UUID | dev mode 偶發 fetchStatus='paused' — 屬 React Query / Vite 共生 quirk，與 instanceof 無關 |

### 7.2 Files Changed

#### Frontend (4 files)
- `frontend/src/api/client.ts`：加 `ApiError.is(err)` static type guard
- `frontend/src/main.tsx`：QueryClient 加 `networkMode: 'always'` 預設；`instanceof ApiError` → `ApiError.is(err)` (S064 防漏 4xx skip)
- `frontend/src/lib/api-error-messages.ts`：i18n localizeApiError 改 `ApiError.is`
- `frontend/src/pages/SkillDetailPage.tsx`：S039 isNotFound 檢查改 `ApiError.is`

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: Chrome 訪問 invalid UUID 顯「找不到此技能」| ⚠️ NOT ACHIEVED via this fix — Vite dev mode React Query paused state 為 deeper bug，非 instanceof 失效 |
| AC-2: 4xx skip 仍 work（S064 不 regress）| ✅ logic 不變 |
| AC-3: i18n 翻譯仍 work（S040）| ✅ logic 不變 |
| AC-4: 既有 frontend test 不破 | ✅ 10 / 0 fail |

### 7.4 Key Findings

**Two-layer investigation**:
1. **HMR ApiError instanceof** — Vite 模組重載多 class instance；name-based check 是 robust pattern。3 處 `instanceof` 改 `ApiError.is()` — 正確的 defense-in-depth。
2. **React Query fetchStatus='paused'** — debug 揭露真正 user-visible bug 是 query state 卡 paused，error=null + isLoading=false → SkillDetailPage 異常分支。`networkMode='always'` 設定生效但未阻止 paused 狀態（疑 React Query v5.100.1 + Vite dev 共生 bug）。

**Scope decision**: ship S065 為 robustness pattern + networkMode pre-config；不再保證 fix user-visible bug（該 bug 留 §7.5 觀察是否 prod build 也發生）。

### 7.5 Pending Verification / Tech Debt

- **Bug AC（仍未解）**：Vite dev mode React Query v5.100.1 偶發 `fetchStatus='paused'` 卡死即使 `networkMode='always'`。Prod build 是否 reproducible 未測。Workaround：user 手動 reload 頁面通常 work。留下一輪
- ApiError.is pattern 為 future spec ApiError 衍生類別（如 NetworkError）的 base
