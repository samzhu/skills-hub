# S193-T01: Backend Score Logging and Ordering

## 對應規格
S193：Semantic Search Score Transparency

## 這個 task 要做什麼
這個 task 完成後，`GET /api/v1/search/semantic?q=...` 的 response 仍照現有 `score = 1 - distance` 回傳，但後端 log 會多出前 3 筆 hit 的 id、name、score。下一次正式站搜尋問題發生時，Cloud Run log 能直接看到 `resultsCount` 與 top hit scores，不需要只靠 request URL 推理。此 task 不調整 threshold、不改 SQL 公式、不加入 keyword overlap guard。

## 使用者情境（BDD）
Given（前提）DB 裡有 public skill `產生字幕檔`，embedding 代表影片 / 音訊轉字幕用途  
When（動作）匿名使用者呼叫 `GET /api/v1/search/semantic?q=影片轉字幕` 與 `GET /api/v1/search/semantic?q=甜點`  
Then（結果）`影片轉字幕` 對該 skill 的 score 高於 `甜點`  
And（而且）response 依 score 由高到低排序，後端 log 有 `query`、`resultsCount`、`topHitIds`、`topHitNames`、`topHitScores`，且沒有 token、cookie、email 或 request body

## 研究來源
- `docs/grimo/specs/2026-05-17-S193-semantic-search-score-transparency.md` §2.4 / §7：POC 已確認 Spring AI PgVectorStore M6 使用 `distance = embedding <=> queryEmbedding`、`score = 1 - distance`。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`：現有 SQL 已從 `skills.embedding` 查詢，並以 `ORDER BY distance` 排序。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java`：現有 `toResult()` 已把 distance 轉成 response score。
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchFromSkillsTest.java`：可沿用 Testcontainers + MockMvc fixture pattern seed skills embedding。

## 先做 POC
- POC：not required — S193 spec §7 已拆本 repo 實際 Spring AI M6 jar，且本 task 只在既有 service 上補 log 與測試。
- Fixture：
  - `subtitle-positive-query`: `影片轉字幕` → subtitle skill score 較高
  - `subtitle-weak-query`: `甜點` → subtitle skill 可以過門檻但 score 較低
- POC 跑完必須印出：不適用；若 implementation 發現現有 fixture 無法穩定區分分數，先回 spec 記錄原因，不要改 production threshold 湊結果。

## 正式程式怎麼做
- Class / file 名稱：`SemanticSearchService.java`
- 入口：`SemanticSearchService.search(String query, int topK)`
- 必要行為：
  - 保留 `SEMANTIC_SEARCH_SQL_FROM_SKILLS` 的 `<=>`、`< ?`、`ORDER BY distance` 路徑。
  - `topHits` 只取前 `topK` 筆；log top 3。
  - structured log 新增 `topHitIds`、`topHitNames`、`topHitScores`。
  - log 不記 token、cookie、email、request body，也不記完整 result list。
- Response / log 欄位：
  - `score`: `1.0d - distance`
  - `topHitIds`: 前 3 筆 `SemanticSearchResult.id`
  - `topHitNames`: 前 3 筆 `SemanticSearchResult.name`
  - `topHitScores`: 前 3 筆 `SemanticSearchResult.score`

## 單元測試 / 整合測試
- `SkillSemanticHitTest` 或既有 search test
  - `@DisplayName("AC-S193-1: score equals one minus cosine distance")`
- `SemanticSearchFromSkillsTest`
  - `@DisplayName("AC-S193-3: positive query score is higher than weak query score")`
  - `@DisplayName("AC-S193-4: semantic response is ordered by score descending")`
- `SemanticSearchServiceLoggingTest` 或同等 log capture test
  - `@DisplayName("AC-S193-2: semantic search log includes top hit scores without sensitive fields")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/*SemanticSearch*Test.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SkillSemanticHitTest.java`（若採獨立 record unit test）
- `docs/grimo/specs/2026-05-17-S193-semantic-search-score-transparency.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SemanticSearch*"`

## 前置條件
- 無

## 狀態
pending（待做）
