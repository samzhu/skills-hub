# S176-T01: Drop skills.name unique constraint

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
`backend/src/main/resources/db/migration/V1__initial_schema.sql` 目前讓 `skills.name` 是 `UNIQUE`，正式站第二次上傳同名 skill 會被 PostgreSQL 擋掉。本 task 新增 V25 migration，移除 `skills_name_key`，並把 legacy `/skills/{author}/{name}` 查詢改成 deterministic row，避免重名後 `LIMIT 1` 受資料頁面順序影響。

## 使用者情境（BDD）
Given（前提）資料庫已經有一筆 `skills.name="transcribe-video"` 的 row  
When（動作）再插入另一筆不同 `id`、相同 `name="transcribe-video"` 的 row  
Then（結果）第二筆 INSERT 成功，DB 有兩筆同名 skill  
And（而且）查 `pg_constraint` 時，`skills_name_key` 不存在  
And（而且）同 author + same name 呼叫 `findByAuthorAndName(author, name)` 不會丟錯，會依 `created_at DESC, id DESC` 回傳固定一筆

## 研究來源
- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md §2.1`
- PostgreSQL ALTER TABLE docs: `ALTER TABLE ... DROP CONSTRAINT IF EXISTS constraint_name`
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`：目前 inline `name VARCHAR(64) NOT NULL UNIQUE`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`：目前 `findByName` Javadoc 依賴 name unique；`findByAuthorAndName` 無 `ORDER BY`

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）

## 先做 POC
- POC：not required — 用現有 Flyway migration + RepositorySliceTestBase / Testcontainers 驗證即可。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/resources/db/migration/V25__drop_skill_name_unique.sql`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`
- 入口：Flyway migration、Spring Data JDBC repository
- 必要行為：
  - V25 migration 執行 `ALTER TABLE skills DROP CONSTRAINT IF EXISTS skills_name_key;`
  - 移除未使用的 `findByName(String name)`，或改寫成非唯一 contract；production code 不可再假設 name unique。
  - `findByAuthorAndName` SQL 加 `ORDER BY created_at DESC, id DESC LIMIT 1`，Javadoc 寫明這是 legacy alias，ID route 才是 canonical identity。
- DB 欄位：
  - `skills.name`: `NOT NULL` 保留；`UNIQUE` 移除。

## 單元測試 / 整合測試
- `SkillNameUniquenessMigrationTest`
  - `@DisplayName("AC-S176-6: Flyway schema removes skills_name_key and allows duplicate skill names")`
- `SkillRepositoryDuplicateNameTest`
  - `@DisplayName("AC-S176-7: author/name legacy lookup returns deterministic latest row for duplicate names")`

## 會改哪些檔案
- `backend/src/main/resources/db/migration/V25__drop_skill_name_unique.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillNameUniquenessMigrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillRepositoryDuplicateNameTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillNameUniquenessMigrationTest" --tests "*SkillRepositoryDuplicateNameTest"`

## 前置條件
- 無

## 狀態
pending（待做）
