# S096g2-T01: Request aggregate + service + claim/fulfill endpoints + schema

## Spec
S096g2 — Request Board Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096g2-request-board-full.md`）

## BDD（涵蓋的 AC）

**AC-1**: 建立 request happy path → 201 + `{id}` + DB row OPEN/vote_count=0 + outbox RequestPostedEvent
**AC-2**: title 長度上限 (cap 200) → 400 title_too_long
**AC-3**: 列表 votes desc 預設 + ?sort=created
**AC-4**: 列表 ?status=OPEN filter
**AC-7**: Claim OPEN → IN_PROGRESS + claimer_id
**AC-8**: 已 claim → 409 request_already_claimed
**AC-9**: Release claim → status OPEN + claimer_id=null；非 claimer → 403 not_request_claimer
**AC-10**: Fulfill happy path → status FULFILLED + fulfilled_skill_id
**AC-11**: Fulfill 非 claimer → 403 not_request_claimer
**AC-12**: Fulfill 非 PUBLISHED skill → 400 skill_not_publishable
**AC-13**: Delete own OPEN → 204；非 requester → 403；非 OPEN status → 409 cannot_delete_active_request
**AC-14**: ModularityTests 仍 PASS

**不在本 task scope**（T02 處理）：AC-5/6 vote toggle 走 RequestVoteService 獨立 service。

## Implementation outline

### Aggregate

```java
@Table("requests")
class Request extends AbstractAggregateRoot<Request> implements Persistable<String> {
    @Id String id;
    String title;        // ≤ 200
    String description;  // ≤ 5000
    String requesterId;
    String status;       // OPEN / IN_PROGRESS / FULFILLED
    String claimerId;    // null when OPEN/FULFILLED
    String fulfilledSkillId;  // null until FULFILLED
    @ReadOnlyProperty long voteCount;  // 走 raw SQL atomic UPDATE，aggregate save 不覆蓋
    Instant createdAt;
    Instant updatedAt;

    static Request create(...) { /* 驗 title/description + registerEvent(RequestPostedEvent) */ }
    void claim(String userId) { /* OPEN→IN_PROGRESS guard + registerEvent(RequestClaimedEvent) */ }
    void release(String userId) { /* IN_PROGRESS guard + claimer 比對 + registerEvent(RequestReleasedEvent) */ }
    void fulfill(String userId, String skillId) { /* claimer 比對 + status guard + registerEvent(RequestFulfilledEvent) */ }
}
```

### Schema migration V10__create_request_tables.sql

```sql
CREATE TABLE requests (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    description TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 5000),
    requester_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','FULFILLED')),
    claimer_id VARCHAR(255),
    fulfilled_skill_id VARCHAR(36) REFERENCES skills(id) ON DELETE SET NULL,
    vote_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_requests_status ON requests (status);
CREATE INDEX idx_requests_votes_desc ON requests (vote_count DESC, created_at DESC);

-- request_votes 表先建好（T02 service 會用），UNIQUE 防 spam
CREATE TABLE request_votes (
    request_id VARCHAR(36) NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    voted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (request_id, user_id)
);
```

### Service / Repository / Controllers

- `RequestRepository` — Spring Data JDBC + 4 derived queries (findAllByOrderByVoteCountDescCreatedAtDesc / findByStatus / sort by createdAt) + `@Modifying @Query incrementVoteCount` (T02 用)
- `RequestService` — create / claim / release / fulfill / delete (state guard + claimer 比對 + skill PUBLISHED 驗 via SkillRepository)
- `RequestCommandController` — POST / POST-claim / DELETE-claim / POST-fulfill / DELETE
- `RequestQueryController` — GET list (sort + status filter) / GET single
- 刪除既有 `community/RequestController.java` stub（被 Command + Query 取代）

### Events (5 records)

- `RequestPostedEvent`
- `RequestClaimedEvent`
- `RequestReleasedEvent`
- `RequestFulfilledEvent`
- `RequestVotedEvent`（T02 用，先建 record）

### Exceptions

- `RequestNotFoundException` (404 request_not_found)
- 復用 IllegalStateException → 409 STATE_CONFLICT (e.g., already_claimed, cannot_delete_active_request)
- 復用 IllegalArgumentException → 400 (title_too_long, skill_not_publishable)
- 新 `NotRequestClaimerException` (403 not_request_claimer)

### Modulith
驗證 community module allowedDependencies = shared::events/api/security + skill::domain + skill::query (查 PUBLISHED status 用) — 與 S096f2 期望一致；本 task 加入 community module 第 2 個 aggregate (existing Collection in S096f2 if shipped)。

### Tests

- `RequestServiceTest` (Testcontainers) — AC-1/2/7/8/9/10/11/12/13
- `RequestQueryControllerTest` (slice + mock) — AC-3/4 sort/filter
- ModularityTests 跑一次確認 boundary 仍乾淨

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/community/Request.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestRepository.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestService.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestCommandController.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java` (new — 取代 RequestController.java stub)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestController.java` (delete)
- `backend/src/main/java/io/github/samzhu/skillshub/community/events/RequestPostedEvent.java` etc (5 records new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/RequestNotFoundException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/NotRequestClaimerException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` (modify)
- `backend/src/main/resources/db/migration/V10__create_request_tables.sql` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/package-info.java` (modify if 需要 — confirm allowedDependencies 含 skill::query)
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestServiceTest.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestQueryControllerTest.java` (new)

## Depends On
none

## Status
✅ shipped 2026-05-03 cron Tick 18

## Result

**Trim from spec template**：
- RequestQueryControllerTest slice test defer — RequestServiceTest 已涵蓋 listRequests 業務邏輯（AC-3/AC-4）；Controller 純 thin pass-through
- Events 5 個放 `community/events/` sub-package 而非 inline `community/`（per skill aggregate 既有 pattern）

**Design deviation**：
- Spring Data JDBC AOT codegen 對多屬性 compound sort（如 `findAllByOrderByVoteCountDescCreatedAtDesc`）產生壞 code（缺逗號）— 改用 `@Query("SELECT ... ORDER BY vote_count DESC, created_at DESC")` annotation 避開 derived query AOT bug
- `vote_count >= 0` CHECK constraint 加在 schema 防 race 出負數；`GREATEST(vote_count - 1, 0)` 在 T02 toggle off 路徑做 application-level guard

**Verification**：
- `RequestServiceTest` 13/13 PASS @ 9.5s（Testcontainers + 真 PostgreSQL）— AC-1/2/3×2/4/7/8/9/10/11/12/13 + getRequest negative
- `ModularityTests` 2/2 PASS — community 模組正式註冊 allowedDependencies = shared::events/api/security + skill::domain + skill::query；boundary 仍乾淨

**Files changed**：
- `backend/src/main/java/io/github/samzhu/skillshub/community/package-info.java` (new — 正式註冊 Modulith module)
- `backend/src/main/java/io/github/samzhu/skillshub/community/Request.java` (new — aggregate root + create/claim/release/fulfill 充血方法)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestRepository.java` (new — 4 個 @Query custom queries)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestService.java` (new — 5 method orchestration + skill::domain SkillRepository.findById cross-module call)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestCommandController.java` (new — POST/DELETE endpoints)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java` (new — GET list + GET single)
- `backend/src/main/java/io/github/samzhu/skillshub/community/events/RequestPostedEvent.java` etc (5 records new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestController.java` (delete — 取代 by Command + Query)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/RequestNotFoundException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/NotRequestClaimerException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` (modify — 加 2 個 mapping)
- `backend/src/main/resources/db/migration/V10__create_request_tables.sql` (new — requests + request_votes)
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestServiceTest.java` (new — 13 tests)
