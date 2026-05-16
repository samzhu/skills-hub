# S185-T01: List Projection Fields

## 對應規格
S185：Skill list/detail projection consistency

## 這個 task 要做什麼
這個 task 完成後，`GET /api/v1/skills` 回同一筆 skill 時，`visibility` 會直接讀 `skills.is_public`，不會再被 `Skill.fromRow(...)` 固定成 `PRIVATE`。同一個 list row 也會補上 detail 已經有的版本欄位：`verified`、`latestVersionPublishedAt`、`license`、`compatibility`、`versionCount`、`openFlagCount`。使用者在瀏覽列表和詳情頁看到的公開狀態與版本資訊會一致。

## 使用者情境（BDD）
Given（前提）DB 有一筆 `PUBLISHED` skill，`is_public=TRUE`，`acl_entries` 沒有 `public:*:read`，並且有 1 筆 `skill_versions` row，`frontmatter={"license":"MIT","compatibility":["codex"]}`、`published_at='2026-05-15T21:06:42Z'`

When（動作）anonymous 呼叫 `GET /api/v1/skills?page=0&size=10`

Then（結果）response `content[0].visibility` 是 `PUBLIC`

And（而且）response `content[0]` 含 `verified=true`、`latestVersionPublishedAt='2026-05-15T21:06:42Z'`、`license='MIT'`、`compatibility=['codex']`、`versionCount=1`

And（而且）同 id 的 `GET /api/v1/skills/{id}` 回相同 `visibility/verified/latestVersionPublishedAt/versionCount/license/compatibility`

## 研究來源
- `docs/grimo/specs/2026-05-16-S185-skill-list-detail-projection-consistency.md`
- `docs/grimo/specs/archive/2026-05-04-S119-list-rating-projection.md`
- `docs/grimo/specs/archive/2026-05-15-S177-is-public-first-search-visibility.md`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- Spring Framework JDBC `RowMapper` docs: https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html
- Spring Data JDBC mapping docs: https://docs.spring.io/spring-data/relational/reference/jdbc/mapping.html

## 先做 POC
- POC：not required — 不新增 framework / SDK；`SkillQueryService` 已用 `NamedParameterJdbcTemplate` raw SQL，S119 已證明 `fromRow` backward-compatible overload pattern 可行。

## 正式程式怎麼做
- Class / file 名稱：`Skill.java`
- 入口：`Skill.fromRow(...)`
- 必要行為：
  - 新增 backward-compatible overload，參數包含 `Boolean publicSkill`。
  - 既有 overload 繼續 delegate，預設 `publicSkill=false`，避免一次改動所有 fixture callsite。
- Finding / response / DB 欄位：
  - `visibility`: 由 `skills.is_public` 轉成 `Skill.visibility` JSON。

- Class / file 名稱：`SkillQueryService.java`
- 入口：`search(...)` / `mapSkillRow(...)`
- 必要行為：
  - list SQL SELECT 加 `s.is_public`。
  - list SQL 用 `skill_versions` / `flags` 取得 latest version published time、frontmatter、version count、open flag count。
  - `mapSkillRow(...)` 呼叫新的 `Skill.fromRow(..., publicSkill, ...)`。
  - `mapSkillRow(...)` 呼叫 `withDetail(...)` 填 list-safe S142b 欄位。
  - list path 不呼叫 `withViewerPermissions(...)`。
- Finding / response / DB 欄位：
  - `verified`: `status == PUBLISHED && riskLevel != null`
  - `latestVersionPublishedAt`: latest `skill_versions.published_at`
  - `license`: latest `skill_versions.frontmatter.license`
  - `compatibility`: latest `skill_versions.frontmatter.compatibility`
  - `versionCount`: `COUNT(*) FROM skill_versions WHERE skill_id=s.id`
  - `openFlagCount`: `COUNT(*) FROM flags WHERE skill_id=s.id AND status='OPEN'`

## 單元測試 / 整合測試
- `SkillQueryServiceVisibilityTest`
  - `@DisplayName("AC-S185-1: list row visibility comes from skills.is_public")`
  - `@DisplayName("AC-S185-2: list row S142b fields match detail source fields")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryServiceVisibilityTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.query.SkillQueryServiceVisibilityTest`

## 前置條件
- 無

## 狀態
pending（待做）
