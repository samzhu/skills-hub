# S186-T01: Schema + aggregate guard

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，DB 的 `skills` row 會多 `embedding_content / embedding / embedding_model / embedding_updated_at`，並建立 `idx_skills_embedding_hnsw`。`vector_store` 會由 V27 migration drop 掉；`Skill` aggregate 仍不新增任何 `embedding*` Java field。`SkillRepository.findByAuthorAndName` 必須改成 explicit column list，避免 alias detail 查詢拉到 embedding 大欄位。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.id='skill-docker'`，且 `skills.embedding_content='docker compose helper'`、`skills.embedding` 非 null
When（動作）後端呼叫 `skillRepo.findById("skill-docker")`，更新一般欄位後 `skillRepo.save(skill)`
Then（結果）DB 的 `skills.embedding_content` 仍是 `docker compose helper`，`skills.embedding` 仍非 null
And（而且）`Skill` class 沒有 `embedding` / `embeddingContent` / `embeddingUpdatedAt` 欄位
And（而且）`SkillRepository.findByAuthorAndName` 的 `@Query` 不含 `SELECT * FROM skills`

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` §2.5：`SkillEmbeddingColocationPocTest` 已證明 unmapped DB column 不會被 aggregate save 清掉。
- `docs/grimo/development-standards.md`：Spring Data JDBC aggregate 用 `@Table("skills")` + `ListCrudRepository`，repository query 要對齊 mapped fields。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`：`findByAuthorAndName` 目前使用 `SELECT * FROM skills`。

## 先做 POC
- POC：not required — §2.5 POC-S186-1 已通過。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/resources/db/migration/V27__skill_embedding_columns.sql`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`
- 入口：Flyway migration + Spring Data JDBC repository。
- 必要行為：
  - `ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_content TEXT`
  - `ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding VECTOR(768)`
  - `ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64)`
  - `ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_updated_at TIMESTAMPTZ`
  - `CREATE INDEX IF NOT EXISTS idx_skills_embedding_hnsw ON skills USING HNSW (embedding vector_cosine_ops)`
  - `DROP TABLE IF EXISTS vector_store`
  - `findByAuthorAndName` 改 explicit column list，欄位至少包含 `Skill` 目前 mapped fields：`id/name/description/author/category/category_display/author_name_snapshot/status/latest_version/risk_level/download_count/average_rating/review_count/acl_entries/is_public/owner_id/created_at/updated_at/version`
- DB 欄位：
  - `embedding_content`: latest embedding text source；不輸出 API
  - `embedding`: pgvector `VECTOR(768)`
  - `embedding_model`: 寫入 embedding 的 model name
  - `embedding_updated_at`: embedding 更新時間

## 單元測試 / 整合測試
- `SkillRepositoryEmbeddingColumnTest`
  - `@DisplayName("AC-S186-1: Skill aggregate save preserves unmapped embedding columns")`
  - `@DisplayName("AC-S186-1: SkillRepository.findByAuthorAndName does not select star from skills")`
- `SkillEmbeddingMigrationTest`
  - `@DisplayName("AC-S186-1: V27 creates skills embedding columns and drops vector_store")`

## 會改哪些檔案
- `backend/src/main/resources/db/migration/V27__skill_embedding_columns.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillRepositoryEmbeddingColumnTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/db/SkillEmbeddingMigrationTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.domain.SkillRepositoryEmbeddingColumnTest --tests io.github.samzhu.skillshub.db.SkillEmbeddingMigrationTest`

## 前置條件
- 無

## 狀態
pending（待做）
