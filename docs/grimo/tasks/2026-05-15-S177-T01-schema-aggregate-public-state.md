# S177-T01: Schema + aggregate public state

## 對應規格
S177：is_public-first Search Visibility

## 這個 task 要做什麼
`skills.is_public` 目前是從 `acl_entries` 裡的 `public:*:read` 算出來的 generated column。這個 task 要新增 V26 migration，把 `skills.is_public` 改成 ordinary boolean，並新增 `vector_store.is_public` 搜尋索引用欄位；同時讓 `Skill.create(...)` 直接保存 PUBLIC/PRIVATE 狀態，不再靠 public ACL 字串推導。

## 使用者情境（BDD）
Given（前提）v25 schema 的 `skills.is_public` 是 generated column，且 `skills.acl_entries` / `vector_store.acl_entries` 可能含 `public:*:read`
When（動作）Flyway 套用 V26
Then（結果）`information_schema.columns.generation_expression` 對 `skills.is_public` 為 null 或空值
And（而且）`vector_store` 有 `is_public BOOLEAN NOT NULL DEFAULT FALSE` 欄位
And（而且）兩張表的 `acl_entries` 都不含 `public:*:read`

Given（前提）Alice 建立 `visibility=PUBLIC` 的 skill
When（動作）`Skill.create(CreateSkillCommand)` 回傳 aggregate
Then（結果）aggregate 的 `is_public` 欄位會持久化成 true
And（而且）`acl_entries` 只包含 Alice 的 explicit owner 權限，不包含 `public:*:read`

## 研究來源
- `docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md §4.1`
- `backend/src/main/resources/db/migration/V16__rbac_acl_projection.sql`
- `backend/src/main/resources/db/migration/V2__add_acl_entries.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- PostgreSQL JSONB `-` operator 與 generated columns：`docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md §2.1`

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）

## 先做 POC
- POC：not required — migration 與 aggregate 行為都可用現有 Testcontainers / repository slice 測試直接驗證。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/resources/db/migration/V26__is_public_first_search_visibility.sql`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Visibility.java`
- 入口：Flyway migration、`Skill.create(CreateSkillCommand)`
- 必要行為：
  - V26 drop/recreate `skills.is_public` 為 ordinary `BOOLEAN NOT NULL DEFAULT FALSE`。
  - V26 add `vector_store.is_public BOOLEAN NOT NULL DEFAULT FALSE` 與 partial index。
  - V26 cleanup `skills.acl_entries` / `vector_store.acl_entries` 的 `public:*:read`。
  - `Skill` 新增 `@Column("is_public") private boolean publicSkill` 與 getter。
  - PUBLIC create 設 `publicSkill=true`；PRIVATE create 設 `false`。
  - `Skill.create` 產生的 `aclEntries` 不含 public pseudo entry。
- DB 欄位：
  - `skills.is_public`: ordinary boolean。
  - `vector_store.is_public`: ordinary boolean。

## 單元測試 / 整合測試
- `IsPublicFirstMigrationTest`
  - `@DisplayName("AC-S177-1: migration converts skills.is_public to ordinary boolean and adds vector_store.is_public")`
  - `@DisplayName("AC-S177-1: migration removes public ACL pseudo entry from skills and vector_store")`
- `SkillAggregateTest`
  - `@DisplayName("AC-S177-1b: public skill create persists is_public without public ACL entry")`
  - `@DisplayName("AC-S177-1b: private skill create persists is_public false without public grant")`

## 會改哪些檔案
- `backend/src/main/resources/db/migration/V26__is_public_first_search_visibility.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Visibility.java`
- `backend/src/test/java/io/github/samzhu/skillshub/db/IsPublicFirstMigrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*IsPublicFirstMigrationTest" --tests "*SkillAggregateTest"`

## 前置條件
- 無

## Status
pending
