# S186-T03: Embedding write path

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，`SearchProjection` 不再把 `Document` 寫進 `vector_store`，而是把 latest SKILL.md frontmatter 轉成 `skills.embedding_content` 並更新 `skills.embedding_*`。新版 publish 或 reactivate 會更新 embedding；suspend 會清掉 embedding，讓 semantic search 不命中下架 skill。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.id='skill-docker'`，`embedding_content='old text'`，`status='PUBLISHED'`
When（動作）`SkillVersionPublishedEvent` 帶 latest SKILL.md frontmatter `name='docker-helper'`、`description='Compose deploy helper'`
Then（結果）`skills.embedding_content='docker-compose-helper docker-helper Compose deploy helper'`
And（而且）`skills.embedding` 非 null，`skills.embedding_model` 有 model 名稱，`skills.embedding_updated_at` 變新
And（而且）`skills.name/status/is_public/acl_entries` 不因 re-embed 被覆蓋

Given（前提）`skills.id='skill-docker'` 有 embedding
When（動作）`SkillSuspendedEvent` 被處理
Then（結果）`skills.embedding` 與 `skills.embedding_content` 變成 null

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` §4.4：`SearchEmbeddingRepository.upsertEmbedding / clearEmbedding` interface。
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`：目前 listener 用 `SkillshubPgVectorStore.add/delete`。
- `docs/grimo/specs/archive/2026-05-08-S157-semantic-search-not-functional.md` §7：semantic search 需要 deterministic embedding fixture 才能自動驗證。

## 先做 POC
- POC：not required — T03 使用 T01/T02 已建立的 `skills.embedding_*` schema 與 test fixture。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/search/SearchEmbeddingRepository.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- 入口：`SearchProjection.onSkillCreated / onVersionPublished / onSkillSuspended / onSkillReactivated`
- 必要行為：
  - 新增 `SearchEmbeddingRepository`，用 `NamedParameterJdbcTemplate` 執行 `UPDATE skills SET embedding_content, embedding, embedding_model, embedding_updated_at WHERE id=:skillId`
  - `embedding` 使用 `EmbeddingModel.embed(content)` 的 `float[]` 包成 `PGvector`
  - `embedding_content = skills.name + " " + frontmatter.name + " " + frontmatter.description`
  - `onSkillCreated` 可用 event name/description 建初始 embedding；`onVersionPublished` 使用 latest frontmatter；`onSkillReactivated` 使用 `Skill` row + latest version frontmatter；`onSkillSuspended` 清空 embedding
  - 不透過 `skillRepo.save(skill)` 更新 embedding，避免觸發 aggregate event 或覆蓋 domain state
- DB 欄位：
  - `embedding_content`: 產生向量的實際文字
  - `embedding_model`: 目前 embedding model 名稱；若 production code 拿不到 provider name，先用固定 config value 或 class simple name，並在 test 斷言非 blank
  - `embedding_updated_at`: update 時間

## 單元測試 / 整合測試
- `SearchEmbeddingRepositoryTest`
  - `@DisplayName("AC-S186-5: upsertEmbedding updates only skills embedding columns")`
  - `@DisplayName("AC-S186-5: clearEmbedding nulls embedding columns without changing visibility or ACL")`
- `SearchProjectionEmbeddingWriteTest`
  - `@DisplayName("AC-S186-5: SkillVersionPublishedEvent embeds latest SKILL.md frontmatter into skills row")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchEmbeddingRepository.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchEmbeddingRepositoryTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionEmbeddingWriteTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SearchEmbeddingRepositoryTest --tests io.github.samzhu.skillshub.search.SearchProjectionEmbeddingWriteTest`

## 前置條件
- S186-T01 PASS
- S186-T02 PASS

## 狀態
pending（待做）
