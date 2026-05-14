# S169-T01: Role matrix 與 ACL projection 同步

## Spec
S169 — CQRS permission contract

## BDD
Given `Role` 目前只有 OWNER/VIEWER 且 ACL projection 只更新 `skills.acl_entries`
When 新增 `Role.EDITOR` 並執行 grant projection
Then `Role.EDITOR.permissions()` 僅回傳 `read`、`write`（不含 `delete`）
And grant user/group 後 `skills.acl_entries` 與 `vector_store.acl_entries` 會同步成相同集合
And `user:<id>:delete` 不會出現在 EDITOR grant 的 ACL entries

## Target Files
- `backend/src/main/resources/db/migration/V23__skill_grants_editor_role.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/Role.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillGrantDomainTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListenerTest.java`

## Depends On
- none

## Status
pending
