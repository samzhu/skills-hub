# S170-T01: Data foundation and Group aggregate

## Spec
S170 — Group tree principal model

## BDD
Given admin creates root Group `Acme`
When admin creates child Group `Cloud` under `Acme`
Then `groups.parent_id` and `group_closure` contain the root, child, and self rows
And duplicate sibling slug, cycle move, and duplicate generated id cases are rejected or retried as specified

## Target Files
- `backend/src/main/resources/db/migration/V23__group_tree_principals.sql`
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupIdGenerator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/Group.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupKind.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupRepository.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/org/GroupServiceTest.java`

## AC
- AC-1
- AC-2
- AC-8
- AC-12
- AC-15

## Depends On
- none

## Status
pending

## Preflight

- 2026-05-14: `rg --files backend/src/main/resources/db/migration` shows `V22__request_voting_board_simplification.sql` already exists from S156c. Use `V23__group_tree_principals.sql` for this task.
