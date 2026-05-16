# S186-T02: Semantic SQL from skills

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，`GET /api/v1/search/semantic?q=...` 會直接查 `skills.embedding`，同一筆 `skills` row 會同時提供排序距離、公開/授權判斷、以及 result card 欄位。測試資料不需要建立任何 `vector_store` row，response 也不再透過 `skillRepo.findAllById` 補欄位。

## 使用者情境（BDD）
Given（前提）DB 有 public skill `skill-docker`，`status='PUBLISHED'`、`is_public=true`、`embedding` 與 query 相近
When（動作）anonymous 呼叫 `GET /api/v1/search/semantic?q=部署容器&limit=10`
Then（結果）HTTP 200 response 包含 `{"id":"skill-docker","name":"docker-compose-helper","author":"u_current","category":"devops","downloadCount":7}`
And（而且）DB 不需要任何 `vector_store` row

Given（前提）DB 有 private skill `skill-private`，`is_public=false`，`acl_entries=["user:u_alice:read"]`，`embedding` 與 query 相近
When（動作）anonymous 呼叫同一 endpoint
Then（結果）response 不包含 `skill-private`
And（而且）以 Alice principal 呼叫時 response 包含 `skill-private`

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` §4.2 / §4.3：`SkillSemanticHit` DTO 與 single-table semantic SQL。
- `docs/grimo/specs/archive/2026-05-03-S107-semantic-search-projection-fields.md` §7：result card 欄位應從 canonical skill row 來，不可靠 `vector_store.metadata`。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`：目前透過 `SkillshubPgVectorStore` 取 docs，再 `skillRepo.findAllById` 補欄位。

## 先做 POC
- POC：not required — §2.5 POC-S186-2 / POC-S186-3 已通過。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- 入口：`SemanticSearchService.search(String query, int topK)`
- 必要行為：
  - 使用 `EmbeddingModel.embed(query)` 產生 `PGvector queryEmbedding`
  - 使用 `NamedParameterJdbcTemplate` 或 `JdbcTemplate` 查 `skills`
  - SQL 條件包含 `status='PUBLISHED'`、`embedding IS NOT NULL`、`is_public = TRUE OR acl_entries ??| :aclPatterns`
  - `aclPatterns` 由 `PrincipalContextService.currentPrincipalKeys()` 加 `:read` 組成；anonymous 為空 array 時只看 public
  - `score = 1.0 - distance`
  - 不呼叫 `SkillshubPgVectorStore.builder(...)`
  - 不呼叫 `skillRepo.findAllById(...)`
- Response / DB 欄位：
  - `id/name/description/author/category/categoryDisplay/latestVersion/riskLevel/downloadCount`: 直接來自 `skills`
  - `distance`: `skills.embedding <=> :queryEmbedding`
  - `score`: `1.0 - distance`

## 單元測試 / 整合測試
- `SemanticSearchFromSkillsTest`
  - `@DisplayName("AC-S186-2: semantic search returns public skill from skills.embedding without vector_store")`
  - `@DisplayName("AC-S186-3: anonymous semantic search hides private skill stored in skills.embedding")`
  - `@DisplayName("AC-S186-3: granted user semantic search sees private skill from skills.acl_entries")`
  - `@DisplayName("AC-S186-8: semantic result card fields come from the same skills row")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchFromSkillsTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SemanticSearchFromSkillsTest`

## 前置條件
- S186-T01 PASS

## 狀態
pending（待做）
