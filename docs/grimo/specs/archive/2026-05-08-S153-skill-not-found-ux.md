# S153: Skill Detail 404 UX — 區分「找不到」vs「真的錯誤」訊息

> Spec: S153 | Size: XS(3) | Status: ✅ shipped 2026-05-08
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 訪問不存在的 skill ID `/skills/non-existent-skill-id-12345` 顯示「載入技能時發生錯誤 / 請稍後重試或重新整理頁面」，使用者誤以為是 transient error，會反覆 refresh 浪費流量。

---

## 1. Goal

讓 `SkillDetailPage` 在「找不到 / 無權限 / 格式錯誤」三種 API 回應下，都顯示**乾淨的「找不到此技能」訊息**，不要叫使用者重試。

**為什麼重要：**
- 「請稍後重試」對「永遠不存在的東西」是錯的訊息，使用者反覆 refresh 浪費流量、徒增挫敗
- 三種 API status code (400/403/404) 全是「使用者打了不能看的 URL」的不同表達，UX 不該分歧

**非目標：**
- 不改後端 API 行為（403/400 vs 404 的選擇是 security-vs-clarity 的另一個問題，本 spec 不動；見 §6）
- 不改成功路徑的 UI

---

## 2. Approach

### 2.1 現況實測（LAB 2026-05-08）

| URL → 對應 API call | 實測 API response | 前端目前文案 | 期望 |
|---------------------|-------------------|--------------|------|
| `/skills/non-existent-skill-id-12345` → `GET /api/v1/skills/non-existent-skill-id-12345` | **400** `{"error":"VALIDATION_ERROR","message":"Invalid format for parameter 'id'"}` | 「載入技能時發生錯誤 / 請稍後重試」 | 「找不到此技能」 |
| `/skills/00000000-0000-0000-0000-000000000000` → 同上路徑 | **403** `{"status":403,"error":"Forbidden","message":"Access Denied"}` | 「載入技能時發生錯誤 / 請稍後重試」 | 「找不到此技能」 |
| `/skills/test-author/test-name` → `GET /api/v1/skills/test-author/test-name` | **404** `{"error":"NOT_FOUND","message":"Skill not found: ..."}` | 「找不到此技能」（無 retry 提示）| ✅ 維持 |
| 真實 transient 5xx / network error | （無法穩定重現）| 「載入技能時發生錯誤 / 請稍後重試」 | ✅ 維持（給使用者 retry hint 是合理的）|

### 2.2 程式碼定位

`frontend/src/pages/SkillDetailPage.tsx:76-92`：

```tsx
if (error || !skill) {
  const isNotFound = ApiError.is(error) && error.status === 404
  return (
    <AppShell>
      ...
      <p className="text-lg font-medium">
        {isNotFound ? '找不到此技能' : '載入技能時發生錯誤'}
      </p>
      {!isNotFound && (
        <p className="mt-1 text-sm">請稍後重試或重新整理頁面</p>
      )}
      <Link to="/browse" className="mt-2 text-sm text-primary hover:underline">返回列表</Link>
    </AppShell>
  )
}
```

`isNotFound` 的判斷只 cover 404，不 cover 400 / 403。

### 2.3 修正

把 `isNotFound` 擴展為 `isUnviewable`：

```tsx
const isUnviewable =
  ApiError.is(error) && [400, 403, 404].includes(error.status)
```

判斷依據：
- **400**：使用者直接在 URL 列輸入不符合 UUID 格式的字串 → 「URL 寫錯了」
- **403**：UUID 格式對但 ACL 不允許讀 → 對 user 而言無法區分「不存在」vs「無權限」（且本來就不該透露），統一視為「找不到」
- **404**：標準的「不存在」

剩下的 5xx / network error 仍走「載入錯誤 / 請稍後重試」。

### 2.4 為什麼 400 也合併

`@PathVariable UUID id` 的 Spring binding 失敗 → 400 VALIDATION_ERROR。從 user 視角這就是「這個 URL 沒指到任何東西」，與「不存在」語義一致。把 400 視為 retry-able 沒意義（使用者重試 100 次，path 格式還是錯的）。

### 2.5 對齊其他類似頁面

audit 同時掃 `SkillDetailPage`-like patterns 的 404 處理：

| 頁面 | 現況 | 處理 |
|------|------|------|
| `CollectionDetailPage` | S150 已加 `isError \|\| !collection` → "找不到此集合"（無區分 status）| ✅ 已正確（S150 spec 寫的就是這個 pattern）|
| `RequestBoardPage` 子頁 | 暫無 detail 頁 | 不適用 |
| `SkillVersionDiffPage` | 待 audit（不在本 spec 範圍）| 留 follow-up |

本 spec 只動 `SkillDetailPage`。

---

## 3. Acceptance Criteria

```
AC-1: 格式錯誤 ID → 「找不到此技能」
  Given 使用者訪問 /skills/non-existent-skill-id-12345（非 UUID 格式）
  When SkillDetailPage 收到 API 400 VALIDATION_ERROR
  Then 顯示「找不到此技能」
  And 不顯示「請稍後重試或重新整理頁面」
  And 顯示「返回列表」連結至 /browse

AC-2: 不存在 UUID → 「找不到此技能」
  Given 使用者訪問 /skills/00000000-0000-0000-0000-000000000000
  When SkillDetailPage 收到 API 403 Access Denied
  Then 顯示「找不到此技能」
  And 不顯示「請稍後重試」

AC-3: canonical alias 404 行為不變
  Given 使用者訪問 /skills/test-author/test-name
  When SkillDetailPage 收到 API 404 NOT_FOUND
  Then 顯示「找不到此技能」（與 AC-1/AC-2 一致）

AC-4: 真實 5xx / network error 仍提示 retry
  Given API 回 500 或 fetch network error
  When SkillDetailPage 渲染錯誤態
  Then 顯示「載入技能時發生錯誤」 + 「請稍後重試或重新整理頁面」
```

驗證指令：`cd frontend && npm test`（per qa-strategy.md；新增 vitest case 在 `SkillDetailPage.test.tsx` 已存在的 test file）

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/pages/SkillDetailPage.tsx` | line 77：`isNotFound` 改名為 `isUnviewable`；判斷由 `error.status === 404` → `[400, 403, 404].includes(error.status)`；變數名 + 條件 swap 完成 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | 新增 4 個 AC test case（mock fetch 對應 status code，assert text content） |

---

## 5. Test Plan

### 5.1 自動化（vitest + MockMvc 風格 fetch mock）

`SkillDetailPage.test.tsx` 新增 describe block：

```ts
describe('SkillDetailPage — S153 unviewable status mapping', () => {
  it.each([
    [400, 'VALIDATION_ERROR', 'Invalid format for parameter id'],
    [403, 'Forbidden', 'Access Denied'],
    [404, 'NOT_FOUND', 'Skill not found: x'],
  ])('AC-1/2/3: status %s → 找不到此技能 (no retry hint)', async (status, errorCode, message) => {
    fetchMock.mockResponseOnce(
      JSON.stringify({ error: errorCode, message }),
      { status, headers: { 'content-type': 'application/json' } },
    )
    renderPage('/skills/some-id')
    await waitFor(() => screen.getByText('找不到此技能'))
    expect(screen.queryByText(/請稍後重試/)).not.toBeInTheDocument()
  })

  it('AC-4: 500 → "載入技能時發生錯誤" + retry hint', async () => {
    fetchMock.mockResponseOnce(
      JSON.stringify({ error: 'INTERNAL', message: 'oops' }),
      { status: 500 },
    )
    renderPage('/skills/some-id')
    await waitFor(() => screen.getByText('載入技能時發生錯誤'))
    expect(screen.getByText(/請稍後重試/)).toBeInTheDocument()
  })
})
```

### 5.2 手動 deploy 後驗證

- [ ] `https://.../skills/non-existent-skill-id-12345` → 「找不到此技能」（無 retry 提示）
- [ ] `https://.../skills/00000000-0000-0000-0000-000000000000` → 「找不到此技能」
- [ ] `https://.../skills/test-author/test-name` → 「找不到此技能」（不變）
- [ ] 真實 skill detail 頁仍正常 render

---

## 6. Verification

| 項目 | 結果 |
|------|------|
| `npx vitest run src/pages/SkillDetailPage.test.tsx` | ✅ 9/9 pass（既有 5 + 新 2 個 S153 + 既有 share owner-only 2） |
| 視覺檢查 SkillDetailPage.tsx line 76-95 | `isNotFound` 已改 `isUnviewable`；條件 `[400, 403, 404].includes(error.status)`；retry 提示僅在非 unviewable 顯示 |

裁切：spec §2.5 提到 SkillVersionDiffPage 的類似 audit 留 follow-up；本 spec 不動。

---

## 7. Result

**Shipped 2026-05-08** — 2 file changes，9/9 vitest pass。

### 7.1 程式變動

- `frontend/src/pages/SkillDetailPage.tsx` line 76-95
  - `isNotFound` → `isUnviewable`，judge condition 由 `error.status === 404` 擴展為 `[400, 403, 404].includes(error.status)`
  - 加註解說明「對使用者來說 400/403/404 都是『找不到』，retry 提示只給真實 5xx/network」
- `frontend/src/pages/SkillDetailPage.test.tsx`
  - 既有 4 個 error path test 保留（404 + 500 + 返回列表 + share owner-only ×2）
  - 新增 2 個 S153 case：
    - `S153 AC-1`: 400 VALIDATION_ERROR → 「找不到此技能」, no retry hint
    - `S153 AC-2`: 403 Access Denied → 「找不到此技能」, no retry hint

### 7.2 行為驗證

| AC | 結果 |
|----|------|
| AC-1（400 格式錯誤）| ✅ vitest 覆蓋 |
| AC-2（403 ACL 拒讀）| ✅ vitest 覆蓋 |
| AC-3（404 標準）| ✅ 既有 AC-1 (404 not-found) 覆蓋 |
| AC-4（500 真實錯誤仍顯 retry）| ✅ 既有 AC-2 (500) 覆蓋 |

### 7.3 Trade-off / 後續

- 本 spec 不動後端 — `/api/v1/skills/{nonexistent-uuid}` 回 403 是 ACL filter 副作用，保留可避免存在性 enumeration 攻擊。Frontend 統一處理足夠。
- `SkillVersionDiffPage` 的 404 處理 audit 留 follow-up，未來如再發現相同問題可開新 spec 套同模式。

---

## 8. 相關 spec / 後續 follow-up

- **S152**（SPA fallback for unknown routes）：本 spec 處理「使用者打對 SPA route，backend API 回非預期 status」；S152 處理「使用者打到根本不存在的 URL」。互不相依，可並行。
- **後端 403 vs 404 trade-off**（不在本 spec 範圍）：`/api/v1/skills/{nonexistent-uuid}` 回 403 Access Denied 是 ACL filter 的副作用 — 保留可避免「skill 是否存在」的 enumeration 攻擊；改 404 對 user 訊息更清楚但稍微洩漏存在性。本 spec 走「frontend 統一處理」path，不動後端，避免動 ACL 行為。若未來決定動，另開 spec。
- **S150（CollectionDetailPage）**已用 `isError \|\| !collection` 全 catch，無此問題；無需改動。
