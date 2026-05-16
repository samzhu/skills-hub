# S178-T03: Remove Search Route And Intent Summary

## 對應規格
S178：Browse Search Entry Point Cleanup

## 這個 task 要做什麼
這個 task 完成後，使用者直接開 `/search` 或 `/search?q=dd` 會看到現有 404 頁，不會再進 `SearchResultsPage`，也不會被 redirect 到 `/browse`。同時刪除只服務 `/search` 的 intent summary frontend/backend 能力，讓 production code 不再註冊 `POST /api/v1/search/intent`。

## 使用者情境（BDD）
Given（前提）使用者直接開 `/search?q=dd`

When（動作）React Router resolve path

Then（結果）頁面不 render `SearchResultsPage`

And（而且）頁面不 redirect 到 `/browse`

And（而且）頁面落到現有 `NotFoundPage`

Given（前提）dedicated `/search` page 已移除

When（動作）跑 production source scan

Then（結果）`frontend/src` 不再有 production import `IntentSummaryCard` 或 `useSearchIntent`

And（而且）`backend/src/main/java` 不再有 `SearchIntentController` 或 `POST /api/v1/search/intent`

## 研究來源
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`
- `frontend/src/App.tsx`
- `frontend/src/pages/SearchResultsPage.tsx`
- `frontend/src/hooks/useSearchIntent.ts`
- `frontend/src/components/IntentSummaryCard.tsx`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchNativeConfig.java`

## 先做 POC
- POC：not required — 不新增 framework / SDK；此 task 是刪除已知無入口的 route、component、hook、controller 與對應 tests/config expectation。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/App.tsx`
- 入口：React Router route table
- 必要行為：
  - 移除 `SearchResultsPage` import。
  - 移除 `<Route path="/search" element={<SearchResultsPage />} />`。
  - 保留 existing `path="*"` → `NotFoundPage`。

- Class / file 名稱：frontend search UI 支線
- 入口：`SearchResultsPage` / `useSearchIntent` / `IntentSummaryCard`
- 必要行為：
  - 刪除 `frontend/src/pages/SearchResultsPage.tsx` 與 test。
  - 刪除 `frontend/src/hooks/useSearchIntent.ts`。
  - 刪除 `frontend/src/components/IntentSummaryCard.tsx` 與 test。
  - `frontend/src/api/search.ts` 只保留 `fetchSemanticSearch`，移除 `IntentResponse` / `fetchSearchIntent`。

- Class / file 名稱：backend intent summary 支線
- 入口：`POST /api/v1/search/intent`
- 必要行為：
  - 刪除 `SearchIntentController`、`SearchIntentService`。
  - 刪除 `SearchNativeConfig`，前提是該 config 只註冊 `SearchIntentService.LlmIntentOutput`。
  - `AiModelConfig` 移除 `searchIntentChatClient` bean；確認沒有其他 consumer。
  - 更新或刪除只驗 intent summary 的 backend tests。
  - 更新 `SearchConfigRegressionTest`、`StructuredOutputNativeHintCoverageTest`、`AiModelConfigTest` 中與 `SearchIntentService.LlmIntentOutput` / `searchIntentChatClient` 相關的 expectation。

## 單元測試 / 整合測試
- `App.test.tsx`
  - `@DisplayName("AC-S178-8: /search falls through to NotFoundPage")`
- Source scan command
  - `rg "/search\\?|/api/v1/search/intent|IntentSummaryCard|useSearchIntent|SearchIntentController|SearchIntentService" frontend/src backend/src/main/java backend/src/test/java`
  - 預期：production code 無 match；測試與 docs 若仍需保留，必須只指向 S178 task/spec/changelog 文字，不可是 production caller。

## 會改哪些檔案
- `frontend/src/App.tsx`
- `frontend/src/App.test.tsx`
- `frontend/src/pages/SearchResultsPage.tsx`
- `frontend/src/pages/SearchResultsPage.test.tsx`
- `frontend/src/hooks/useSearchIntent.ts`
- `frontend/src/components/IntentSummaryCard.tsx`
- `frontend/src/components/IntentSummaryCard.test.tsx`
- `frontend/src/api/search.ts`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchNativeConfig.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/ai/AiModelConfig.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchIntentServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigRegressionTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/aot/StructuredOutputNativeHintCoverageTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/ai/AiModelConfigTest.java`

## 驗證方式
執行：

```bash
cd frontend && npm test -- App && npm run verify
cd backend && ./gradlew test --tests "*Search*" --tests "*AiModelConfigTest" --tests "*StructuredOutputNativeHintCoverageTest"
```

## 前置條件
- S178-T01 PASS
- S178-T02 PASS

## Status
PASS

## Result
Date: 2026-05-16
Test: `AC-S178-8: /search?q=dd falls through to NotFoundPage` (`frontend/src/App.test.tsx`)

RED:
- `cd frontend && npm test -- App` → FAIL：`/search?q=dd` 實際 render `SearchResultsPage`，找不到 `404`。

GREEN:
- `cd frontend && npm test -- App` → PASS（2 files / 17 tests）
- `cd frontend && npm run verify` → PASS（eslint + `tsc -b`）
- `rg "SearchResultsPage|IntentSummaryCard|useSearchIntent|fetchSearchIntent|IntentResponse|SearchIntent|searchIntentChatClient|SearchNativeConfig|/api/v1/search/intent" frontend/src backend/src/main/java backend/src/test/java` → no matches
- `cd backend && ./gradlew test --tests "*Search*" --tests "*AiModelConfigTest" --tests "*StructuredOutputNativeHintCoverageTest"` → BUILD SUCCESSFUL

Files changed:
- `frontend/src/App.tsx`：移除 `/search` route 與 `SearchResultsPage` import。
- `frontend/src/App.test.tsx`：新增 `/search?q=dd` 落到 `NotFoundPage` 的 route test。
- `frontend/src/api/search.ts`：只保留 `fetchSemanticSearch`，移除 intent summary API client。
- `frontend/src/pages/SearchResultsPage.tsx`、`frontend/src/pages/SearchResultsPage.test.tsx`、`frontend/src/hooks/useSearchIntent.ts`、`frontend/src/components/IntentSummaryCard.tsx`、`frontend/src/components/IntentSummaryCard.test.tsx`：刪除 dedicated `/search` frontend 支線。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentController.java`、`SearchIntentService.java`、`SearchNativeConfig.java`：刪除 `POST /api/v1/search/intent` backend 支線與 native hint。
- `backend/src/main/java/io/github/samzhu/skillshub/shared/ai/AiModelConfig.java`：移除 `searchIntentChatClient` bean。
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigRegressionTest.java`、`backend/src/test/java/io/github/samzhu/skillshub/shared/aot/StructuredOutputNativeHintCoverageTest.java`、`backend/src/test/java/io/github/samzhu/skillshub/shared/ai/AiModelConfigTest.java`：移除 intent summary / AOT hint / bean expectation。
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchIntentServiceTest.java`：刪除已移除 service 的測試。
- `frontend/src/pages/docs/RestApiPage.tsx`：REST quick reference 移除已刪除的 intent endpoint row。
- `frontend/src/components/SkillCard.tsx`：移除已刪除 `SearchResultsPage` 的註解引用。

Notes: T04 仍需同步 `/docs/semantic-search` CTA、architecture/debugging docs 與 Playwright E2E browse network contract。
