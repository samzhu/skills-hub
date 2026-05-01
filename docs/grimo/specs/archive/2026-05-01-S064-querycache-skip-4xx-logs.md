# S064: QueryCache Logger Skip 4xx ApiError

> Spec: S064 | Size: XS(5) | Status: 🔲 Planning — target ship `v2.41.0`
> Trigger: 2026-05-01 /loop tick 37 — Chrome console 大量 `[QueryCache] Array(2) ApiError: Skill not found: 00000000-...` 等 console.error 訊息。`main.tsx` 全域 QueryCache logger 對所有 query 錯誤 log，包含 UI 已優雅處理的 4xx（404 not-found 顯示「找不到此技能」per S039）。Console pollution 干擾 dev 找真錯。

---

## 1. Goal

`main.tsx` QueryCache subscribe callback 加判斷：跳過 UI 預期已處理的 4xx ApiError，只 log 5xx / network 錯誤。

---

## 2. Approach

### 2.1 Code diff

```diff
 queryClient.getQueryCache().subscribe((event) => {
   if (event.type === 'updated' && event.action.type === 'error') {
+    const err = event.action.error
+    // S064: 跳過 4xx ApiError — UI 已負責處理（如 404 not-found 顯示友善 state per S039）；
+    //       保留 5xx / network / 非 ApiError 錯誤 log 利 dev 找真問題
+    if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
+      return
+    }
-    console.error('[QueryCache]', event.query.queryKey, event.action.error)
+    console.error('[QueryCache]', event.query.queryKey, err)
   }
 })
```

### 2.2 為何不直接拿掉 logger

5xx / network 錯誤 是 unexpected — dev 需從 console 看訊號。維持 logger 但精挑只 log 真問題。

### 2.3 為何 status range 用 [400, 500)

4xx 全部視為「client 已知問題，UI 應處理」：
- 400 VALIDATION_ERROR / CONSTRAINT_VIOLATION — form / publish flow 有錯誤 banner
- 401 / 403 — auth 場景（dev LAB mode 不會遇）
- 404 — S039 detail page 顯示友善 state
- 409 STATE_CONFLICT / DUPLICATE_RESOURCE / CONCURRENT_MODIFICATION — UI 顯 i18n 訊息
- 413 PAYLOAD_TOO_LARGE — UI banner

5xx Server / network 為意外。

### 2.4 為何 NOT 加 console.warn for 4xx

冗訊：4xx 訊息對 dev debug 4xx 場景時可從 Network panel 看；不必雙重 log。

---

## 3. SBE Acceptance Criteria

### AC-1: 訪問 invalid skill UUID — 不再 console.error

```gherkin
When  user 訪問 /skills/00000000-0000-0000-0000-000000000000
Then  detail page 顯示「找不到此技能」（S039 既有不破）
And   console 不出現 "[QueryCache] ApiError: Skill not found"
```

### AC-2: 5xx 仍 log

```gherkin
Given backend 暫時 503
When  query 失敗
Then  console.error "[QueryCache] ApiError: API error 503"
```

### AC-3: 既有 frontend test 不破

```gherkin
When  npm test
Then  10 / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/main.tsx`：QueryCache subscribe 加 4xx 跳過 + import `ApiError`

### 5.2 Test
- 既有 test 不破即可；E2E 由 Chrome console pattern 驗

### 5.3 Docs
- CHANGELOG `v2.41.0`
- spec-roadmap M60

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | guard + Chrome console 驗 | AC-1~3 | 🔲 |

---

## 7. Implementation Results

> Status: 🔲 Pending
