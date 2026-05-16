# S178-T02: HomePage Request Routing State Machine

## 對應規格
S178：Browse Search Entry Point Cleanup

## 這個 task 要做什麼
這個 task 完成後，`/browse` 搜尋框空白時只打 catalog list API；搜尋框有字時只打 semantic API。semantic 回空或錯誤時，頁面仍留在 semantic mode，不再偷偷改打 `/api/v1/skills?keyword=...`。

## 使用者情境（BDD）
Given（前提）使用者在 `/browse`

When（動作）搜尋框空白

Then（結果）前端送 `GET /api/v1/skills?page=0&size=20&sort=downloadCount%2Cdesc`

And（而且）不送 `/api/v1/search/semantic`

Given（前提）使用者在 `/browse`

When（動作）快速輸入 `d` 再輸入 `dd`，並等待 debounce 完成

Then（結果）前端只送 `GET /api/v1/search/semantic?q=dd`

And（而且）不送 `GET /api/v1/skills?keyword=dd`

## 研究來源
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`
- `frontend/src/pages/HomePage.tsx`
- `frontend/src/hooks/useSkillList.ts`
- `frontend/src/hooks/useSemanticSearch.ts`

## 先做 POC
- POC：not required — 既有 HomePage tests 已用 `globalThis.fetch` 攔 request URL；直接補 RED test 可以重現 dual-query bug。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/HomePage.tsx`
- 入口：`HomePage`
- 必要行為：
  - `hasSearchInput = query.trim().length > 0` 是唯一 mode 判斷。
  - `isCatalogMode = !hasSearchInput`；`isSemanticMode = hasSearchInput`。
  - list query 不再傳 `keyword`。
  - list query 在 `isCatalogMode === false` 時 `enabled:false`。
  - semantic query 使用 `debouncedQuery`。
  - semantic empty/error 不 fallback 到 keyword list。
  - 搜尋框 placeholder 改成 `描述你想完成的任務或搜尋技能...`。

## 單元測試 / 整合測試
- `HomePage.test.tsx`
  - `@DisplayName("AC-S178-1: initial browse uses catalog API only")`
  - `@DisplayName("AC-S178-2/3: search input uses only final debounced semantic API")`
  - `@DisplayName("AC-S178-4: semantic zero result does not keyword-fallback")`
  - `@DisplayName("AC-S178-5: semantic error does not keyword-fallback")`
  - `@DisplayName("AC-S178-6: clearing search returns to unfiltered catalog API")`
- `SearchBar.test.tsx`
  - `@DisplayName("AC-S178-12: placeholder matches semantic entry")`

## 會改哪些檔案
- `frontend/src/pages/HomePage.tsx`
- `frontend/src/pages/HomePage.test.tsx`
- `frontend/src/components/SearchBar.tsx`
- `frontend/src/components/SearchBar.test.tsx`
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`

## 驗證方式
執行：`cd frontend && npm test -- HomePage SearchBar`

## 前置條件
- S178-T01 PASS

## 狀態
PASS（2026-05-16）

## Result

改動：
- `frontend/src/pages/HomePage.tsx` 改成 explicit state machine：`hasSearchInput`、`isCatalogMode`、`isSemanticMode`、`isDebouncingSearch`。
- `/browse` 搜尋框有字時，`useSkillList(..., { enabled:false })` 立即停掉 catalog API，且不再傳 `keyword`。
- semantic request 改用 `useDebouncedValue(trimmedQuery, 300)`；快速輸入 `d` → `dd` 時只送 `q=dd`。
- semantic 空結果和錯誤都留在 semantic mode，不 fallback 到 `/api/v1/skills?keyword=...`。
- `SearchBar` placeholder 改成 `描述你想完成的任務或搜尋技能...`。

驗證：

```bash
cd frontend && npm test -- useDebouncedValue useSkillList HomePage SearchBar
```

結果：PASS — 4 test files / 20 tests。

實際測到的 request contract：
- `/browse` 初始載入會打 `/api/v1/skills?...sort=downloadCount%2Cdesc`，不打 `/api/v1/search/semantic`。
- 搜尋 `dd` 會打 `/api/v1/search/semantic?q=dd`，不打 `/api/v1/skills?keyword=dd`。
- semantic 回 `[]` 或 500 時也不打 `/api/v1/skills?keyword=dd`。
