# S186-T07: Migration tests final schema

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，舊的 V2 / V26 migration tests 會對齊 S186 後的最終 schema：`skills.acl_entries` 與 `skills.is_public` 仍要被驗證，但測試不再查詢或寫入已由 V27 刪除的 `vector_store`。跑 backend test 時會看到這兩個 migration test class 通過，而不是在 `vector_store` table 不存在時丟 SQL exception。

## 使用者情境（BDD）
Given（前提）Flyway 已套用到 V27，DB 裡有 `skills.embedding_*` 欄位且 `vector_store` table 已被刪除
When（動作）執行 `IsPublicFirstMigrationTest` 與 `V2MigrationTest`
Then（結果）測試只驗證最終仍存在的 `skills.acl_entries`、`skills.is_public` 與 `idx_skills_acl_entries`
And（而且）測試 source 不再 `INSERT INTO vector_store`、`SELECT FROM vector_store` 或 `DELETE FROM vector_store`

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` §7.4：`./scripts/verify-all.sh` 的 V01/V03 失敗都來自舊 migration tests 還要求 `vector_store` 存在。
- `backend/src/main/resources/db/migration/V27__skill_embedding_columns.sql`：S186 final schema 執行 `DROP TABLE IF EXISTS vector_store`。
- `backend/src/test/java/io/github/samzhu/skillshub/db/SkillEmbeddingMigrationTest.java`：已負責驗證 V27 建立 `skills.embedding_*` 並刪除 `vector_store`。

## 先做 POC
- POC：not required — 這是 test drift 修正；S186-T01 已用 `SkillEmbeddingMigrationTest` 驗證 final schema。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java`
  - `backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java`
- 入口：backend JUnit migration tests。
- 必要行為：
  - `IsPublicFirstMigrationTest` 的欄位 metadata test 只查 `skills.is_public`。
  - `IsPublicFirstMigrationTest` 的 cleanup test 只建立/更新/刪除 `skills` row，確認 `public:*:read` 被移除且 explicit read grant 留下。
  - `V2MigrationTest` 保留 `skills.acl_entries` metadata / backfill 測試。
  - `V2MigrationTest` 移除 `vector_store.acl_entries` metadata 與 owner backfill 測試，因為 final schema 已無該 table；V27 drop 行為由 `SkillEmbeddingMigrationTest` 覆蓋。

## 單元測試 / 整合測試
- `IsPublicFirstMigrationTest`
  - `@DisplayName("AC-S177-1: migration keeps skills.is_public as ordinary boolean")`
  - `@DisplayName("AC-S177-1: migration removes public ACL pseudo entry from skills")`
- `V2MigrationTest`
  - `@DisplayName("AC-1: skills.acl_entries JSONB NOT NULL DEFAULT '[]' + GIN(default jsonb_ops)")`
  - `@DisplayName("AC-2: skills 既有 row backfill 為 [\"user:<author>:read|write|delete\"]")`

## 會改哪些檔案
- `backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java`
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md`
- `docs/grimo/tasks/2026-05-16-S186-T07-migration-tests-final-schema.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.db.IsPublicFirstMigrationTest --tests io.github.samzhu.skillshub.db.V2MigrationTest`

通過條件：兩個 migration test class 皆通過，且 `rg -n "vector_store" backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java` 無輸出。

## 前置條件
- S186-T06 PASS

## 狀態
PASS

## Result

Date: 2026-05-17

RED:

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.db.IsPublicFirstMigrationTest --tests io.github.samzhu.skillshub.db.V2MigrationTest
```

Result: FAIL — 6 tests completed, 4 failed。`IsPublicFirstMigrationTest` 還在查 `vector_store.is_public` / `DELETE FROM vector_store`；`V2MigrationTest` 還在查 `vector_store.acl_entries` / `INSERT INTO vector_store`。S186 V27 已刪除該 table，所以 final schema 下這些 expectation 必定失敗。

GREEN:

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.db.IsPublicFirstMigrationTest --tests io.github.samzhu.skillshub.db.V2MigrationTest
```

Result: PASS — `BUILD SUCCESSFUL in 2m 5s`。

Text check:

```bash
rg -n "vector_store" backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java
```

Result: PASS — no output.

Files changed:

- `backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java`
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md`
- `docs/grimo/tasks/2026-05-16-S186-T07-migration-tests-final-schema.md`
