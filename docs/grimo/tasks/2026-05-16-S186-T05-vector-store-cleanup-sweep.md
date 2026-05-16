# S186-T05: Vector-store cleanup sweep

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，runtime code 和 backend tests 不再讀寫 `vector_store`，`SkillshubPgVectorStore.java` 會被刪除。Test fixture / reset allowlist / search tests 都要改成 seed skill 後寫 `skills.embedding_*`，避免新 schema 已 drop `vector_store` 但測試還在寫舊表。

## 使用者情境（BDD）
Given（前提）S186 T01-T04 已完成
When（動作）執行 `rg -n "vector_store|SkillshubPgVectorStore" backend/src/main/java backend/src/test/java`
Then（結果）production semantic search / ACL projection / test fixture 不再依賴 `vector_store`
And（而且）保留的字串只允許出現在舊 Flyway migration 或 archived spec，不出現在 active runtime path

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` AC-S186-6。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`：S186 後 runtime 不再需要。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`：reset allowlist 需要移除 `vector_store`。
- `backend/src/test/java/io/github/samzhu/skillshub/search/*`：多個測試目前直接 seed / assert `vector_store`。

## 先做 POC
- POC：not required — T01-T04 已讓 production read/write path 改用 `skills.embedding_*`。

## 正式程式怎麼做
- Class / file 名稱：
  - delete `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`
  - update `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`
  - update existing search tests and fixture helpers
- 入口：runtime grep + backend test suite compile。
- 必要行為：
  - 刪除 `SkillshubPgVectorStore.java`
  - 移除 production imports / javadocs 中對 `SkillshubPgVectorStore` 的 runtime reference
  - `TestDataController` reset allowlist 不再包含 `vector_store`
  - tests 不再 `INSERT INTO vector_store` 或 `SELECT FROM vector_store`
  - 若保留歷史字串，應只出現在 migration 檔或 archived docs，不在 active runtime path

## 單元測試 / 整合測試
- `VectorStoreRuntimeRemovalTest`
  - `@DisplayName("AC-S186-6: runtime source tree has no vector_store or SkillshubPgVectorStore dependency")`
- Update existing semantic/search tests to compile and pass after `SkillshubPgVectorStore` deletion.

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/*`
- `backend/src/test/java/io/github/samzhu/skillshub/TestcontainersConfiguration.java`

## 驗證方式
執行：`rg -n "vector_store|SkillshubPgVectorStore" backend/src/main/java backend/src/test/java`

通過條件：只剩明確允許的舊 migration 檔引用；active runtime/test code 不引用 `SkillshubPgVectorStore`，且 `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.*` 通過。

## 前置條件
- S186-T03 PASS
- S186-T04 PASS

## 狀態
pending（待做）
