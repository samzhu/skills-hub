# S169-T05: 403 與 409 語意分流

## Spec
S169 — CQRS permission contract

## BDD
Given permission denial 與 state conflict 都可能在 command path 發生
When caller 執行 owner-only 操作、重複版本上傳、重複 review 等案例
Then permission denial 一律回 403
And duplicate/version state conflict 維持 409
And exception handler 不會把 403/409 誤映射

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandlerTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandControllerSecurityTest.java`

## Depends On
- S169-T04 PASS

## Status
pending
