# S096h2-T02: NotificationProjectionListener 4 個 @ApplicationModuleListener + idempotency

## Spec
S096h2 — Notifications Full Projection（spec doc: `docs/grimo/specs/2026-05-03-S096h2-notifications-projection.md`）

## BDD（涵蓋的 AC）

**AC-1: SkillFlagged → owner 收 notification**
- Given：alice 為 sk1 owner；alice flags_enabled=TRUE
- When：bob 對 sk1 發 POST flag → outbox publish SkillFlaggedEvent
- Then：async ≤ 2s 後 `notifications` 表新增 1 筆 (recipient=alice, category=flags, title 含「你的技能…被標記」, ref_event_id=event id)

**AC-2: ReviewCreated → owner 收**
- Given：alice 為 sk1 owner；bob 不是 owner
- When：bob POST review (rating=5)
- Then：alice 收 (category=reviews, title 含「bob…寫了 5★ 評論」)
- 自我 review (alice 寫自己 skill) → skip（不通知自己）

**AC-3: RequestClaimed → requester 收**
- Given：alice 發 request r1
- When：bob POST claim r1
- Then：alice 收 (category=requests, title 含「bob 認領了你發起的需求」)
- 自我 claim (alice claim 自己的 request) → skip

**AC-4: RequestFulfilled → requester 收**
- Given：alice 發 r1；bob 已 claim
- When：bob POST fulfill r1 with skillId=sk5
- Then：alice 收 (category=requests, title 含「你發起的需求…已完成」+ skill name 若 sk5 存在)

**AC-5: Preferences disabled → 不收**
- Given：alice 設 reviews_enabled=FALSE
- When：bob 對 alice 的 sk1 寫 review
- Then：alice `notifications` 表無新增；其他 categories 不影響

**AC-10: Listener idempotency — outbox redelivery 不重複 INSERT**
- Given：SkillFlaggedEvent 觸發 listener INSERT 1 筆
- When：模擬 outbox redelivery 同 event id（直接二次呼叫 listener method 或 publish 同 event）
- Then：UNIQUE(recipient_id, ref_event_id, category) constraint 攔；DB 仍 1 筆；無 DuplicateKeyException 外洩

## Implementation outline

### `backend/.../notification/NotificationProjectionListener.java` (new)

per spec §4.4 — 4 個 `@ApplicationModuleListener` methods：

```java
@Component
public class NotificationProjectionListener {
    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final SkillRepository skillRepo;        // skill::domain
    private final RequestRepository requestRepo;    // community

    @ApplicationModuleListener
    void onSkillFlagged(SkillFlaggedEvent e) { /* 查 skill.owner → save */ }

    @ApplicationModuleListener
    void onReviewCreated(ReviewCreatedEvent e) { /* skip 自我 review；查 owner → save */ }

    @ApplicationModuleListener
    void onRequestClaimed(RequestClaimedEvent e) { /* skip 自我 claim；save */ }

    @ApplicationModuleListener
    void onRequestFulfilled(RequestFulfilledEvent e) { /* save with optional skill name */ }

    private boolean prefEnabled(String userId, String category) { /* findById fallback default TRUE */ }

    private void save(String recipient, String cat, String title, String body, String skillId, String refEventId) {
        try {
            notifRepo.save(...);
        } catch (DuplicateKeyException ignored) {
            // UNIQUE constraint 防 outbox redelivery
        }
    }
}
```

### Tests `backend/src/test/.../notification/NotificationProjectionListenerTest.java` (new)

- `@SpringBootTest` full bootstrap（Modulith DIRECT_DEPENDENCIES 拉 transitive bean missing — per S098e2 已驗 lesson）
- Testcontainers PostgreSQL
- 6 ACs：AC-1/2/3/4/5/10
- 對 AC-10 ：直接 publish 同 event id 二次（或直接 call listener method 二次），驗 DB 仍 1 筆 + 無 exception
- Self-action skip：AC-2 自我 review / AC-3 自我 claim 應 skip；可用 alice 雙身份測

## Target Files

- `backend/.../notification/NotificationProjectionListener.java` (new — 4 listener methods + 2 helpers)
- `backend/src/test/.../notification/NotificationProjectionListenerTest.java` (new — 6 ACs)

## Depends On
- T01 — schema + aggregates + repos + module wiring 必先

## Status
pending
