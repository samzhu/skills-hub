# S193-T02: Browse Score Visibility

## 對應規格
S193：Semantic Search Score Transparency

## 這個 task 要做什麼
這個 task 完成後，使用者在 `/browse` 輸入搜尋字串時，如果 semantic API 回傳 skill card，卡片上會顯示該筆結果的相符度。現有 `SkillCard` 已支援 `score` prop 與「XX% 相符」badge；此 task 要確認 assembled `/browse` path 真的把 semantic response 的 `score` 傳到 card，並用 Playwright 留下瀏覽器證據。

## 使用者情境（BDD）
Given（前提）使用者開啟 `/browse`，e2e profile 已 seed 可被 semantic search 找到的 public skill  
When（動作）在搜尋框輸入 `images and containers in CI` 或 S193 fixture query  
Then（結果）畫面顯示 skill card，且 card 上顯示 `% 相符`  
And（而且）browser request log 包含 `/api/v1/search/semantic?q=`，不包含 `/api/v1/skills?keyword=`

## 研究來源
- `frontend/src/components/SkillCard.tsx`：`score !== undefined` 時會顯示 `{(score * 100).toFixed(0)}% 相符`。
- `frontend/src/components/SkillCard.test.tsx`：已有 score badge component-level test，可加 S193 AC tag 或補更精準測試名稱。
- `frontend/src/pages/HomePage.tsx`：`/browse` semantic mode 應把 semantic result 的 score 傳進 `SkillCard`。
- `e2e/tests/S140-critical-path-semantic-search.spec.ts`：現有 `/browse` semantic route E2E，可新增 S193 spec 或擴充專用 @S193 測試。

## 先做 POC
- POC：not required — UI component 已有 score prop 行為；本 task 驗 assembled route / browser path。
- Fixture：
  - `semantic-hit-with-score`: semantic API 回傳至少一筆 score → UI 顯示 `% 相符`
  - `keyword-guard`: 同一操作不應打 `/api/v1/skills?keyword=`
- POC 跑完必須印出：不適用；若 Playwright fixture 缺 seed endpoint 或穩定 query，先在本 task 記錄缺口並補 e2e fixture，不要改 production search semantics。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/components/SkillCard.tsx`
  - `frontend/src/pages/HomePage.tsx`
  - `e2e/tests/S193-semantic-search-score.spec.ts`
- 入口：`/browse` 搜尋框 → `useSemanticSearch` → `SkillCard`
- 必要行為：
  - semantic mode 的 card 必須收到 API response `score`。
  - 顯示文字使用繁體中文「相符」。
  - 若 score 是 `0.5336`，UI 可四捨五入顯示 `53% 相符`；不需要顯示完整小數。
  - 不恢復 `/search` route、不 fallback 到 keyword list。
- UI 欄位：
  - `% 相符`: 由 semantic API `score` 計算。

## 單元測試 / 整合測試
- `SkillCard.test.tsx`
  - `it("AC-S193-5: semantic score badge renders match percentage")`
- `e2e/tests/S193-semantic-search-score.spec.ts`
  - `test("AC-S193-5: /browse semantic card shows match percentage @S193 @ac-5 @happy-path @profile-paged")`

## 會改哪些檔案
- `frontend/src/components/SkillCard.test.tsx`
- `frontend/src/pages/HomePage.tsx`（若現有 assembled path 沒傳 score）
- `e2e/tests/S193-semantic-search-score.spec.ts`
- `docs/grimo/specs/2026-05-17-S193-semantic-search-score-transparency.md`

## 驗證方式
執行：`cd frontend && npm test -- SkillCard`

執行：`cd e2e && npx playwright test --grep @S193`

執行：`./scripts/verify-all.sh`

## 前置條件
- S193-T01 PASS

## 狀態
pending（待做）
