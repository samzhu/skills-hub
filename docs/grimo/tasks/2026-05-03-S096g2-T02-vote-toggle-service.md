# S096g2-T02: Vote toggle service + endpoint + race tests

## Spec
S096g2 — Request Board Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096g2-request-board-full.md`）

## BDD（涵蓋的 AC）

**AC-5: Vote toggle on**
- Given：alice 登入；request `r1` vote_count=5；alice 從未 vote
- When：發 `POST /api/v1/requests/r1/vote`
- Then：回 200 + body `{"voted": true, "voteCount": 6}`；DB `request_votes` 新增 (r1, alice)；`requests.vote_count` 變 6；outbox 寫 `RequestVotedEvent {voted: true}`

**AC-6: Vote toggle off — 重複 POST**
- Given：alice 已 vote 過 r1（count=6）
- When：alice 再發 `POST /api/v1/requests/r1/vote`
- Then：回 200 + body `{"voted": false, "voteCount": 5}`；DB `request_votes` 該 row 消失；`requests.vote_count` 變 5

## Implementation outline

### `community/RequestVoteService.java` (new)

```java
@Service
class RequestVoteService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RequestRepository repo;
    private final ApplicationEventPublisher events;

    /**
     * Toggle vote — 走 atomic SQL：
     * 1. INSERT INTO request_votes ... ON CONFLICT (request_id, user_id) DO NOTHING
     *    → updateCount = 1 表新增成功；0 表已 vote 過
     * 2. 若 0：DELETE FROM request_votes WHERE ... → 移除既有 vote
     * 3. UPDATE requests SET vote_count = vote_count + 1 (or - 1) WHERE id = ...
     *
     * 走 Spring `@Transactional` 包整個 toggle，DB 層 UNIQUE constraint 防並發
     * 雙寫（race 時 INSERT ON CONFLICT 是 idempotent）。
     */
    @Transactional
    public VoteResult toggle(String requestId, String userId) {
        var request = repo.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        // INSERT ... ON CONFLICT
        int inserted = jdbc.update(
                "INSERT INTO request_votes (request_id, user_id, voted_at) VALUES (:r, :u, :t) ON CONFLICT DO NOTHING",
                Map.of("r", requestId, "u", userId, "t", java.sql.Timestamp.from(Instant.now())));
        boolean voted;
        long newCount;
        if (inserted == 1) {
            // 新 vote
            jdbc.update("UPDATE requests SET vote_count = vote_count + 1 WHERE id = :id", Map.of("id", requestId));
            voted = true;
        } else {
            // 已 vote → toggle off
            jdbc.update("DELETE FROM request_votes WHERE request_id = :r AND user_id = :u",
                    Map.of("r", requestId, "u", userId));
            jdbc.update("UPDATE requests SET vote_count = GREATEST(vote_count - 1, 0) WHERE id = :id", Map.of("id", requestId));
            voted = false;
        }
        newCount = jdbc.queryForObject("SELECT vote_count FROM requests WHERE id = :id", Map.of("id", requestId), Long.class);
        events.publishEvent(new RequestVotedEvent(requestId, userId, voted, newCount, Instant.now()));
        return new VoteResult(voted, newCount);
    }

    record VoteResult(boolean voted, long voteCount) {}
}
```

### Endpoint

加到 `RequestCommandController` (T01)：

```java
@PostMapping("/{requestId}/vote")
ResponseEntity<RequestVoteService.VoteResult> toggleVote(@PathVariable String requestId) {
    var userId = users.current().userId();
    return ResponseEntity.ok(voteService.toggle(requestId, userId));
}
```

### Tests

- `RequestVoteServiceTest` (Testcontainers) — AC-5/6 + edge cases:
  - happy toggle on → INSERT + UPDATE +1
  - happy toggle off (re-vote) → DELETE + UPDATE -1
  - GREATEST 防 vote_count 變負（race condition reset to 0 case）
  - non-existent request → RequestNotFoundException
  - 模擬 race（兩 thread 同時 INSERT 同 user_id+request_id）→ 第 2 個 ON CONFLICT 應靜默 → 1 vote 寫入

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestVoteService.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestCommandController.java` (modify — 加 vote endpoint)
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestVoteServiceTest.java` (new)

## Depends On
T01（Request aggregate + RequestCommandController + request_votes table 已建）

## Status
✅ shipped 2026-05-03 cron Tick 19

## Result

實作 per spec template — atomic SQL toggle (INSERT ON CONFLICT DO NOTHING + UPDATE +1/-1 with GREATEST guard + DELETE)；ApplicationEventPublisher 直接發 RequestVotedEvent（不走 aggregate outbox 因 vote_count @ReadOnlyProperty + atomic SQL；對齊 Skill downloadCount S076 同 pattern）。

**Verification**：
- `RequestVoteServiceTest` 5/5 PASS @ 9.7s（Testcontainers）— AC-5 toggle on / AC-6 toggle off / 多 user 互不影響 / non-existent → RequestNotFoundException / vote_count CHECK >= 0 schema-level guard
- `ModularityTests` 2/2 PASS

**Files changed**：
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestVoteService.java` (new — atomic SQL toggle + RequestVotedEvent publish)
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestCommandController.java` (modify — 注入 RequestVoteService + 加 POST `/{requestId}/vote` endpoint)
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestVoteServiceTest.java` (new — 5 tests)
