# S169-T02: Group principal 搜尋 ACL 與 SQL 分頁一致性

## Spec
S169 — CQRS permission contract

## BDD
Given S170 `PrincipalContextService` 會回傳 `user:<id>` 與 `group:<id>` principal
When semantic search 與 skills list 以 ACL 過濾資料
Then semantic search 僅回傳 caller principal 可讀的 skill
And SQL 分頁的 `totalElements` 直接反映 ACL 過濾後筆數，不會先取 21 筆再在 Java 端剔除

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillSearchTest.java`

## Depends On
- S169-T01 PASS

## Status
pending
