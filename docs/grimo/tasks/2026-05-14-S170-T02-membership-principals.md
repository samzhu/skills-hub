# S170-T02: Membership and principal context

## Spec
S170 — Group tree principal model

## BDD
Given Bob belongs to `Platform Team`
And Bob also belongs to root Team `AI Enablement`
When `PrincipalContextService.currentPrincipalKeys()` runs
Then Bob receives `user:u_bob`, both direct Group principals, and the physical ancestors
And removing `AI Enablement` does not remove the physical department principals

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupMembershipService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/PrincipalContextService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/UserAddedToGroupEvent.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/UserRemovedFromGroupEvent.java`
- `backend/src/test/java/io/github/samzhu/skillshub/org/GroupMembershipServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/org/PrincipalContextServiceTest.java`

## AC
- AC-3
- AC-4
- AC-5
- AC-6
- AC-14

## Depends On
- S170-T01

## Status
PASS

## Result

Implemented direct Group membership and current principal context.

Files changed:

- `backend/src/main/java/io/github/samzhu/skillshub/org/GroupMembershipService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/UserAddedToGroupEvent.java`
- `backend/src/main/java/io/github/samzhu/skillshub/org/UserRemovedFromGroupEvent.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/PrincipalContextService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/org/GroupMembershipServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/org/PrincipalContextServiceTest.java`

RED:

```bash
./gradlew test --tests "*GroupMembershipServiceTest" --tests "*PrincipalContextServiceTest"
```

Result: FAIL — `compileTestJava` failed because `GroupMembershipService` and `PrincipalContextService` did not exist.

GREEN:

```bash
./gradlew test --tests "*GroupMembershipServiceTest" --tests "*PrincipalContextServiceTest"
```

Result: PASS — Gradle output ended with `BUILD SUCCESSFUL in 1m 47s`.

Notes:

- `GroupMembershipService.addMember(...)` writes one direct `group_members` row and publishes `UserAddedToGroupEvent` only when an insert happens.
- `GroupMembershipService.removeMember(...)` deletes only the selected `(group_id, user_id)` row and publishes `UserRemovedFromGroupEvent` only when a row is removed.
- `PrincipalContextService.currentPrincipalKeys()` returns `user:<id>` plus `group:<ancestorId>` rows by joining `group_members` to `group_closure` and filtering active ancestors.
- The JDBC timestamp binding uses `Timestamp.from(Instant.now())`, matching the existing project rule that raw PostgreSQL JDBC does not infer `Instant` for `TIMESTAMPTZ`.
