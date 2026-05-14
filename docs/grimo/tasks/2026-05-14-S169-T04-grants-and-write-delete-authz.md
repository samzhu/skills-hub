# S169-T04: grants owner-only 與 write/delete 權限邏輯

## Spec
S169 — CQRS permission contract

## BDD
Given owner 可管理 grants，editor 只能編輯 skill 內容
When owner 與 editor 分別呼叫 grants list、skill update、skill delete
Then owner `GET /skills/{id}/grants` 回 200、editor 回 403
And editor `PUT /skills/{id}` 允許、`DELETE /skills/{id}` 拒絕且回 403
And permission denial 不會落到 409 conflict

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantControllerAuthzTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandControllerSecurityTest.java`

## Depends On
- S169-T03 PASS

## Status
pending
