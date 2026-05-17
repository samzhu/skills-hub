# S192-T02: Semantic search result author display fields

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
語意搜尋 API 現在只回 `author`。這個 task 讓 `GET /api/v1/search/semantic` 的每筆結果多回 `authorDisplayName` 與 `authorHandle`，前端 semantic card 就能顯示 `Sam Zhu` 而不是 `u_f7eb3a`。搜尋條件與排序仍只看 embedding/visibility，不可因作者名稱改變。

## 使用者情境（BDD）
Given（前提）Alice 的 skill 由 embedding 命中，DB row 有 `author="u_f7eb3a"`，users row 有 `name="Sam Zhu"` 與 `handle="samzhu"`
When（動作）Bob 呼叫 `GET /api/v1/search/semantic?q=字幕`
Then（結果）每筆 result 包含 `authorDisplayName="Sam Zhu"` 與 `authorHandle="samzhu"`
And（而且）SQL/ranking 不搜尋、不排序作者名稱

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` AC-S192-3
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchResult.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchServiceVisibilityTest.java`

## 先做 POC
- POC：not required — S186 已把 semantic search source of truth 固定為 `skills.embedding`，本 task 只增加 result projection 欄位。

## 正式程式怎麼做
- Class / file 名稱：`SemanticSearchResult`, `SkillSemanticHit`, `SemanticSearchService`
- 入口：`SearchController.semanticSearch(...)`
- 必要行為：
  - SQL SELECT 加上 `author_display_name` / `author_handle` 來源，或用 `UserDisplayService` 對命中結果批次補欄位
  - 不把 author/name/handle 放進 vector content、where search predicate、order by ranking
  - API JSON 保留 `author`，新增 nullable display companion fields

## 單元測試 / 整合測試
- `SemanticSearchServiceVisibilityTest` 或新 `SemanticSearchAuthorDisplayTest`
  - `@DisplayName("AC-S192-3: semantic search result includes author display fields without author-based ranking")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchResult.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/*`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SemanticSearch*"`

## 前置條件
- S192-T01 PASS

## 狀態
pending（待做）
