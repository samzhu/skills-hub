# S178-T01: Debounce Hook And Query Enabled Contract

## 對應規格
S178：Browse Search Entry Point Cleanup

## 這個 task 要做什麼
這個 task 完成後，前端可以在使用者停下輸入 300ms 後才送出語意搜尋 request。`useSkillList` 也會支援 `enabled:false`，讓 `/browse` 進入搜尋模式時可以立即停掉 catalog list API。

## 使用者情境（BDD）
Given（前提）使用者在 `/browse` 搜尋框快速輸入 `d` 再輸入 `dd`

When（動作）300ms debounce 尚未完成

Then（結果）前端不送出 `GET /api/v1/search/semantic?q=d`

And（而且）`useSkillList(params, { enabled: false })` 不呼叫 `fetchSkills`

## 研究來源
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`
- TanStack Query v5 `enabled` docs: https://tanstack.com/query/v5/docs/framework/react/guides/disabling-queries

## 先做 POC
- POC：not required — 使用 React `useEffect` / `useState` 與 TanStack Query v5 既有 `enabled` option，不新增 dependency。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/hooks/useDebouncedValue.ts`
- 入口：`useDebouncedValue(value, delayMs)`
- 必要行為：
  - value 變更後先保留上一個 debounced value。
  - delay 完成才發布新 value。
  - value 變更或 unmount 時清掉 pending timer。
- Class / file 名稱：`frontend/src/hooks/useSkillList.ts`
- 入口：`useSkillList(params, options)`
- 必要行為：
  - 新增 optional `enabled`。
  - 未傳 options 時維持既有自動 fetch 行為。

## 單元測試 / 整合測試
- `useDebouncedValue.test.tsx`
  - `@DisplayName("AC-S178-3: debounce publishes only the final query")`
- `useSkillList.test.tsx`
  - `@DisplayName("AC-S178-2: enabled false stops catalog list fetch")`

## 會改哪些檔案
- `frontend/src/hooks/useDebouncedValue.ts`
- `frontend/src/hooks/useDebouncedValue.test.tsx`
- `frontend/src/hooks/useSkillList.ts`
- `frontend/src/hooks/useSkillList.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- useDebouncedValue useSkillList`

## 前置條件
- 無

## 狀態
PASS（2026-05-16）

## Result

改動：
- 新增 `frontend/src/hooks/useDebouncedValue.ts`。
- 新增 `frontend/src/hooks/useDebouncedValue.test.tsx`，驗證 `d` → `dd` 快速輸入時只發布最後的 `dd`。
- `frontend/src/hooks/useSkillList.ts` 新增 optional `{ enabled }`，未傳 options 時維持既有自動 fetch。
- 新增 `frontend/src/hooks/useSkillList.test.tsx`，驗證 `{ enabled:false }` 不呼叫 `fetchSkills`，既有 caller 仍會 fetch。

驗證：

```bash
cd frontend && npm test -- useDebouncedValue useSkillList HomePage SearchBar
```

結果：PASS — 4 test files / 20 tests。
