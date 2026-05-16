# S185-T02: Category And JSON Contract

## 對應規格
S185：Skill list/detail projection consistency

## 這個 task 要做什麼
這個 task 完成後，`GET /api/v1/categories` 的數字會只計入目前使用者真的能在列表看到的 skills。匿名使用者不會再看到只屬於 private skill 的分類 count。`GET /api/v1/skills` 的 JSON 也會有一個 MockMvc 測試保護：公開狀態與版本欄位要出現，但 `ownerId`、`aclEntries`、`viewerPermissions` 仍然不能出現在 list response。

## 使用者情境（BDD）
Given（前提）DB 有兩筆 `PUBLISHED` skills：`testing-public` 是 `is_public=TRUE, category='testing'`；`video-private` 是 `is_public=FALSE, category='video'`，anonymous 沒有 ACL grant

When（動作）anonymous 呼叫 `GET /api/v1/categories`

Then（結果）response contains `{"name":"testing","count":1}`

And（而且）response 不包含 `{"name":"video","count":1}`

Given（前提）`SkillQueryController.search()` 回一筆 S185 fixture skill

When（動作）MockMvc 呼叫 `GET /api/v1/skills`

Then（結果）response body 包含 `content[0].visibility`、`content[0].verified`、`content[0].latestVersionPublishedAt`、`content[0].versionCount`

And（而且）response body 不包含 `content[0].ownerId`、`content[0].aclEntries`、`content[0].viewerPermissions`

## 研究來源
- `docs/grimo/specs/2026-05-16-S185-skill-list-detail-projection-consistency.md`
- `docs/grimo/development-standards.md` JSON contract test guidance from S165
- `docs/grimo/qa-strategy.md` WEB slice / REPO slice rules
- `docs/grimo/specs/archive/2026-05-15-S177-is-public-first-search-visibility.md`
- `docs/grimo/specs/archive/2026-05-16-S184-api-empty-response-contract.md`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`

## 先做 POC
- POC：not required — category count 只把 `search()` 已存在的 visibility ACL clause 套到 `getCategoryCounts()`；JSON contract test 沿用既有 `WebMvcSliceTestBase` + MockMvc pattern。

## 正式程式怎麼做
- Class / file 名稱：`SkillQueryService.java`
- 入口：`getCategoryCounts()`
- 必要行為：
  - 取得 `principalContextService.currentPrincipalKeys()`。
  - 轉成 read ACL patterns。
  - SQL 加上 `AND (s.is_public = TRUE OR s.acl_entries ??| :aclPatterns)`。
  - category count 不新增 `author` parameter。
- Finding / response / DB 欄位：
  - `CategoryCount.name`: 只來自 visible `PUBLISHED` skills 的 category。
  - `CategoryCount.count`: 同一個 visible set 的 group count。

- Class / file 名稱：`SkillQueryControllerApiContractTest.java`
- 入口：`GET /api/v1/skills`
- 必要行為：
  - Mock service 回一筆含 S185 list-safe fields 的 `Skill`。
  - 用 Spring Boot auto-configured JSON path，也就是 MockMvc response，驗證 list view。
  - 斷言 safe fields 存在、privacy fields 不存在。
- Finding / response / DB 欄位：
  - `visibility/verified/latestVersionPublishedAt/versionCount`: list response 必須有。
  - `ownerId/aclEntries/viewerPermissions`: list response 必須沒有。

## 單元測試 / 整合測試
- `SkillQueryServiceVisibilityTest`
  - `@DisplayName("AC-S185-3: category counts use same visibility filter as skill list")`
- `SkillQueryControllerApiContractTest`
  - `@DisplayName("AC-S185-4: list JSON keeps privacy contract while exposing filled list fields")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryServiceVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`
- `docs/grimo/specs/2026-05-16-S185-skill-list-detail-projection-consistency.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.query.SkillQueryServiceVisibilityTest --tests io.github.samzhu.skillshub.skill.query.SkillQueryControllerApiContractTest`

## 前置條件
- S185-T01 PASS

## 狀態
pending（待做）
