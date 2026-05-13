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
pending
