# S172-T03: Browse 0-result suggestions are actionable

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
修正 `/browse` 搜尋 0 筆時的空狀態。完成後，空狀態右側每一列看起來像 action 的建議，都必須真的可以點或用鍵盤啟動；舊的「切換到語意搜尋模式」如果沒有對應控制，就要移除或換成真動作。

## 使用者情境（BDD）
Given（前提）使用者在 `/browse` 搜尋 `docker` 且結果是 0 筆  
When（動作）空狀態顯示「你可以這樣做」清單  
Then（結果）每個可見建議 row 是 `<button>` 或 `<a href>`，可 focus，可啟動  
And（而且）至少一個 action 可以清除搜尋詞並回到全部技能列表  
And（而且）畫面不再顯示沒有作用的「切換到語意搜尋模式」。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-5、AC-S172-6。
- `frontend/src/components/SkillCardGrid.tsx`：目前傳三個純文字 suggestions。
- `frontend/src/components/EmptyState.tsx`：目前 redirect suggestions render 成 plain `div`。

## 先做 POC
- POC：not required — 這個 task 只擴充既有 props 與 component 渲染，不新增 API 或套件。

## 正式程式怎麼做
- File 名稱：`frontend/src/components/EmptyState.tsx`、`frontend/src/components/SkillCardGrid.tsx`，必要時同步 caller（例如 browse/search page）。
- 入口：`EmptyState` redirect tone；`SkillCardGrid` 0-result branch。
- 必要行為：
  - 將 `suggestions` 型別從 `{ text, hint }` 擴充成可選 `href` 或 `onClick`。
  - 有 `href` 時 render `Link`；有 `onClick` 時 render `button type="button"`。
  - 沒有 action 的 suggestion 不可 render 成看似可點的 row；若仍需純說明，要改成非 action 視覺。
  - `SkillCardGrid` 應接收清除搜尋 callback，例如 `onClearQuery?: () => void`；搜尋頁 caller 把清除 query 的 state function 傳進來。
  - 建議 actions 至少包含「清除關鍵字並瀏覽全部技能」與「發布你自己的技能」。
  - 不再承諾獨立 semantic mode，除非畫面真的有對應 mode control。

## 單元測試 / 整合測試
- `EmptyState.test.tsx`
  - `@DisplayName("AC-S172-5: redirect suggestions with href render as links")`
  - `@DisplayName("AC-S172-5: redirect suggestions with onClick render as buttons")`
- `SkillCardGrid.test.tsx`（若不存在就新增）
  - `@DisplayName("AC-S172-6: zero search result clear action calls onClearQuery")`
  - `@DisplayName("AC-S172-6: zero search result does not show semantic mode promise without control")`

## 會改哪些檔案
- `frontend/src/components/EmptyState.tsx`
- `frontend/src/components/EmptyState.test.tsx`
- `frontend/src/components/SkillCardGrid.tsx`
- `frontend/src/components/SkillCardGrid.test.tsx`
- `frontend/src/pages/*` 使用 `SkillCardGrid` 且持有搜尋 query 的頁面

## 驗證方式
執行：`cd frontend && npm test -- EmptyState.test.tsx SkillCardGrid.test.tsx`

## 前置條件
- 無。

## 狀態
PASS

## Result
Date: 2026-05-14
Test: `npm test -- EmptyState.test.tsx SkillCardGrid.test.tsx` (`frontend/src/components/EmptyState.test.tsx`, `frontend/src/components/SkillCardGrid.test.tsx`)
Files changed:
- `frontend/src/components/EmptyState.tsx` (modified)
- `frontend/src/components/EmptyState.test.tsx` (modified)
- `frontend/src/components/SkillCardGrid.tsx` (modified)
- `frontend/src/components/SkillCardGrid.test.tsx` (new)
- `frontend/src/pages/HomePage.tsx` (modified)
- `frontend/src/pages/SearchResultsPage.tsx` (modified)
- `docs/grimo/tasks/2026-05-14-S172-T03-browse-empty-state-actions.md` (modified)
Notes:
- RED: `cd frontend && npm test -- EmptyState.test.tsx SkillCardGrid.test.tsx` failed 4 S172 assertions because suggestions rendered as plain divs and SkillCardGrid still showed the old semantic-mode row.
- GREEN: `cd frontend && npm test -- EmptyState.test.tsx SkillCardGrid.test.tsx` passed 2 test files / 10 tests.
