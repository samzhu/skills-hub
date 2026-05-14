# S169-T03: ViewerPermissions 合約與 detail 隱私欄位

## Spec
S169 — CQRS permission contract

## BDD
Given skill detail 需要回傳 action 權限而且 API 不可外洩 `aclEntries`
When owner、editor、group viewer 分別讀取同一筆 skill detail
Then response `viewerPermissions` 反映 read/write/delete/share/manage 的正確布林值
And response JSON 不包含 `aclEntries`
And `canShare`、`canManageGrants` 僅 owner 為 true

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillAclReadEvaluator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissions.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissionService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillDetailViewerPermissionsTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillResponsePrivacyTest.java`

## Depends On
- S169-T01 PASS
- S169-T02 PASS

## Status
pending
