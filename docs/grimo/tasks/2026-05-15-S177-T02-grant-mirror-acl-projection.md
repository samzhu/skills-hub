# S177-T02: Grant mirror + ACL projection

## 對應規格
S177：is_public-first Search Visibility

## 這個 task 要做什麼
新增 skill 與公開/轉私人操作必須在同一個 transaction 裡寫好 `skills.is_public`、OWNER grant、public VIEWER grant mirror。`SkillAclProjectionListener` 只負責把 user/group/company explicit grants 展開到 `acl_entries`，不能再 seed OWNER/public grants，也不能把 public grant 展成 `public:*:read`。

## 使用者情境（BDD）
Given（前提）Alice 上傳一個 `visibility=PUBLIC` 的 skill
When（動作）upload transaction commit
Then（結果）`skills.is_public=true`
And（而且）`skill_grants` 有 Alice OWNER grant
And（而且）`skill_grants` 有 public VIEWER grant
And（而且）`skills.acl_entries` 不含 `public:*:read`

Given（前提）Alice owner 一個 private skill
When（動作）Alice 呼叫 `POST /api/v1/skills/{id}/grants` 建立 public VIEWER grant
Then（結果）同一個 transaction commit 後 `skills.is_public=true` 且 public VIEWER grant 存在
When（動作）Alice 呼叫 `DELETE /api/v1/skills/{id}/grants/{publicGrantId}`
Then（結果）同一個 transaction commit 後 `skills.is_public=false` 且 public VIEWER grant 已刪除
And（而且）explicit user/group/company ACL 保留

## 研究來源
- `docs/grimo/specs/2026-05-15-S177-is-public-first-search-visibility.md §4.2a, §4.5`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `docs/grimo/development-standards.md`：service command path 必須在 public `@Transactional` method 入口

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）

## 先做 POC
- POC：not required — 使用既有 `SkillGrantService` / `SkillCommandService` integration tests 即可重現和驗證。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVisibilityChangedEvent.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantIdGenerator.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java`
- 入口：upload command、grant/revoke API service、ACL projection listener、audit listener
- 必要行為：
  - upload PUBLIC：保存 Skill、OWNER grant、public VIEWER grant。
  - upload PRIVATE：保存 Skill、OWNER grant，不保存 public grant。
  - public grant create：呼叫 `Skill.makePublic(actor, publicGrantId)`、保存 public grant、保存 Skill。
  - public grant revoke：呼叫 `Skill.makePrivate(actor)`、刪 public grant、保存 Skill。
  - `SkillAclProjectionListener` rebuild 時排除 `principal_type='public'`。
  - `SkillVisibilityChangedEvent` 進 audit log。
- DB 欄位：
  - `skill_grants.id`: public grant 使用 12 hex opaque id。
  - `skills.acl_entries`: 只放 explicit grants。

## 單元測試 / 整合測試
- `SkillCommandServiceUploadVisibilityTest`
  - `@DisplayName("AC-S177-1b: upload public skill writes is_public owner grant and public grant in one transaction")`
  - `@DisplayName("AC-S177-1b: upload private skill writes is_public false and no public grant")`
- `SkillGrantServiceVisibilityTest`
  - `@DisplayName("AC-S177-7: public grant create updates is_public and public grant without public ACL")`
  - `@DisplayName("AC-S177-7: public grant revoke updates is_public false and keeps explicit ACL")`
- `AuditEventListenerTest`
  - `@DisplayName("AC-S177-7: visibility change writes audit event")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVisibilityChangedEvent.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantIdGenerator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
- `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceUploadVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantServiceVisibilityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/audit/AuditEventListenerTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillCommandServiceUploadVisibilityTest" --tests "*SkillGrantServiceVisibilityTest" --tests "*AuditEventListenerTest"`

## 前置條件
- S177-T01 PASS

## Status
pending
