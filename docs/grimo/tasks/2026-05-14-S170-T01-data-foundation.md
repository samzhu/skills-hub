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
PASS

## Preflight

- 2026-05-14: `rg --files backend/src/main/resources/db/migration` shows `V22__request_voting_board_simplification.sql` already exists from S156c. Use `V23__group_tree_principals.sql` for this task.

## Result

Date: 2026-05-14
Test: `GroupServiceTest` (`backend/src/test/java/io/github/samzhu/skillshub/org/GroupServiceTest.java`)
Files changed:
- `backend/src/main/resources/db/migration/V23__group_tree_principals.sql` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/package-info.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupIdGenerator.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/Group.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupKind.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupStatus.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupRepository.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupService.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/org/GroupServiceTest.java` (new)
Notes:
- RED: `./gradlew test --tests "*GroupServiceTest"` failed at `compileTestJava` because `GroupService`, `GroupRepository`, `GroupKind`, and `GroupIdGenerator` did not exist yet.
- GREEN: `./gradlew test --tests "*GroupServiceTest"` passed after V23 migration and implementation; Gradle output ended with `BUILD SUCCESSFUL in 1m 37s`.
- Official docs checked: https://docs.spring.io/spring-data/relational/reference/jdbc.html — Spring Data JDBC repository support is aggregate-oriented; T01 keeps `group_members` out of the aggregate child collection per S170 §2.
