# S177-T04: Semantic vector visibility

## 對應規格
S177：is_public-first Search Visibility

## 這個 task 要做什麼
Production bug 是 anonymous semantic search 因 `vector_store.acl_entries` 殘留 `public:*:read` 而看到 private skills。這個 task 要讓 semantic search 使用 `vector_store.is_public OR vector_store.acl_entries ??| :readPatterns`，並讓 SearchProjection 寫入 `vector_store.is_public`，使 anonymous 只能搜公開 skill，登入使用者才能額外搜到自己被授權的 private skill。

## 使用者情境（BDD）
Given（前提）private skill B 的 `skills.is_public=false`，且測試 fixture 故意讓 `vector_store.acl_entries` 含 `public:*:read`
When（動作）anonymous 查 `/api/v1/search/semantic?q=<common>`
Then（結果）response 不包含 B

Given（前提）DB 有 public skill A、private shared-to-Bob skill C，兩筆都有 vector row
When（動作）Bob 查 `/api/v1/search/semantic?q=<common>`
Then（結果）response 包含 A 與 C
And（而且）不包含未授權 private skill B

Given（前提）LAB 部署包含 S177 的新 revision
When（動作）執行 `curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=%E5%BD%B1%E7%89%87'`
Then（結果）response 不包含 `1b18ce61-7770-4966-a924-a87b9a8877ed`
And（而且）response 不包含 `bbe2f0c0-1255-4193-841c-376d022296a2`

## 研究來源
- `docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md §4.3, §4.5`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）

## 先做 POC
- POC：not required — current `SkillshubPgVectorStore` 已有 ACL-aware SQL path；本 task 修改同一路徑的欄位與 patterns，可用既有 stub embedding 測試驗證。

## 正式程式怎麼做
- Class / file 名稱：
  - `SkillshubPgVectorStore`
  - `SemanticSearchService`
  - `SearchProjection`
  - `SkillAclProjectionListener`
- 入口：`GET /api/v1/search/semantic`
- 必要行為：
  - `SkillshubPgVectorStore.INSERT_SQL` 支援 `vector_store.is_public`。
  - builder 新增寫入用 `publicSkill(boolean)` 或等價 API。
  - ACL-aware SQL 使用 `JOIN skills s ON s.id = vs.skill_id` 確認 `s.status='PUBLISHED'`。
  - ACL-aware SQL 可讀條件使用 `vs.is_public = TRUE OR vs.acl_entries ??| ?::text[]`。
  - `SemanticSearchService.readPatterns(...)` 不再 append `public:*:read`。
  - `SearchProjection` create/publish/reactivate re-embed 寫 `vector_store.is_public`，不寫 public ACL。
  - `SkillAclProjectionListener` rebuild vector row 時同步 explicit ACL；public visibility 由 `vector_store.is_public` 欄位表達。
- Response / DB 欄位：
  - Semantic response shape 不變。
  - `vector_store.acl_entries` 不含 public pseudo entry。

## 單元測試 / 整合測試
- `SkillshubPgVectorStoreVisibilityTest`
  - `@DisplayName("AC-S177-4: anonymous semantic search ignores public pseudo ACL in vector_store")`
  - `@DisplayName("AC-S177-5: authenticated semantic search returns public and granted private vectors")`
- `SemanticSearchServiceVisibilityTest`
  - `@DisplayName("AC-S177-4: semantic search does not append public pseudo principal")`
- `SearchProjectionVisibilityTest`
  - `@DisplayName("AC-S177-5: search projection writes vector_store is_public and explicit ACL")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStoreVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchServiceVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionVisibilityTest.java`
- `docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md`（§7 production follow-up evidence after deploy）

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillshubPgVectorStoreVisibilityTest" --tests "*SemanticSearchServiceVisibilityTest" --tests "*SearchProjectionVisibilityTest"`

## 前置條件
- S177-T01 PASS
- S177-T02 PASS

## Status
pending
