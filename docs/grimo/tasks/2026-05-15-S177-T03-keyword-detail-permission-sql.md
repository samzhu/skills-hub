# S177-T03: Keyword + detail permission SQL

## 對應規格
S177：is_public-first Search Visibility

## 這個 task 要做什麼
Keyword browse、skill detail read permission、Spring Security permission evaluator 目前都會把 `public:*:read` 加進 ACL patterns。這個 task 要把 read path 改成：公開 skill 看 `is_public=true`，私人 skill 只看 user/group/company explicit ACL；write/delete/suspend/reactivate 不受 public visibility 影響。

## 使用者情境（BDD）
Given（前提）DB 有 public skill A、private skill B、private shared-to-Bob skill C
When（動作）anonymous 查 `/api/v1/skills?keyword=<common>`
Then（結果）response 只包含 A
And（而且）不包含 B 或 C

Given（前提）DB 有 public skill A、private skill B、private shared-to-Bob skill C
When（動作）Bob 查 `/api/v1/skills?keyword=<common>`
Then（結果）response 包含 A 與 C
And（而且）不包含 B

Given（前提）public skill A 的 `acl_entries` 不含 `public:*:read`
When（動作）anonymous GET `/api/v1/skills/{A}`
Then（結果）HTTP 200
And（而且）random user GET private skill B 得 HTTP 403

## 研究來源
- `docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md §4.2, §4.4`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/JdbcSkillAclReadEvaluator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java`

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）

## 先做 POC
- POC：not required — 這是既有 SQL clause 與 permission evaluator 行為修改，可用現有 repository/API/security tests 驗證。

## 正式程式怎麼做
- Class / file 名稱：
  - `SkillQueryService`
  - `JdbcSkillAclReadEvaluator`
  - `SkillPermissionStrategy`
  - `AclPrincipalExpander`
  - `DelegatingPermissionEvaluator`
- 入口：`GET /api/v1/skills`、`GET /api/v1/skills/{id}`、`hasPermission(..., "read")`
- 必要行為：
  - list/keyword SQL 使用 `status='PUBLISHED' AND (is_public = TRUE OR acl_entries ??| :readPatterns)`。
  - anonymous read patterns 可為空；public read 由 `is_public` 命中。
  - `AclPrincipalExpander` / `DelegatingPermissionEvaluator` 不再補 `public:*:read`。
  - write/delete/suspend/reactivate 仍只看 explicit ACL patterns。
- Response / DB 欄位：
  - API response 仍不輸出 `aclEntries`。
  - `viewerPermissions.canEdit/canDelete/canShare` 不因 `is_public=true` 自動變 true。

## 單元測試 / 整合測試
- `SkillQueryServiceVisibilityTest`
  - `@DisplayName("AC-S177-2: anonymous keyword browse returns only public skills")`
  - `@DisplayName("AC-S177-3: authenticated keyword browse returns public and granted private skills")`
- `JdbcSkillAclReadEvaluatorTest`
  - `@DisplayName("AC-S177-6: read permission allows public skill without public ACL entry")`
  - `@DisplayName("AC-S177-6: read permission denies ungranted private skill")`
- `DelegatingPermissionEvaluatorTest`
  - `@DisplayName("AC-S177-6: write permission is not granted by public visibility")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/JdbcSkillAclReadEvaluator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryServiceVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/JdbcSkillAclReadEvaluatorTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluatorTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillQueryServiceVisibilityTest" --tests "*JdbcSkillAclReadEvaluatorTest" --tests "*DelegatingPermissionEvaluatorTest"`

## 前置條件
- S177-T01 PASS

## Status
pending
