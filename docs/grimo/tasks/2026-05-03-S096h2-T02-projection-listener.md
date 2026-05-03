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
✅ completed — 2026-05-03

**Verification**：
- `NotificationProjectionListenerTest` 6/6 PASS @ 15.5s（Testcontainers + Modulith Scenario AFTER_COMMIT async）— AC-1 SkillFlagged → owner / AC-2 ReviewCreated 含 self-review skip / AC-3 RequestClaimed 含 self-claim skip / AC-4 RequestFulfilled 含 skill name / AC-5 preferences disabled skip / AC-10 idempotency
- `ModularityTests` 2/2 PASS（notification 加 4 個跨模組 dep 邊界乾淨）
- `NotificationModuleSmokeTest` 8/8 仍 PASS（adjacent regression check）

**Files changed**：
- `backend/.../notification/NotificationProjectionListener.java` (new — 4 個 @ApplicationModuleListener + isCategoryEnabled/save 兩 helper + DuplicateKeyException catch idempotency)
- `backend/.../notification/package-info.java` (modify — allowedDependencies 加 4 項：skill::domain / community / community::events / review::domain / security)
- `backend/.../community/events/package-info.java` (new — `@NamedInterface("events")` 暴露 5 個 Request event records 給跨模組 consumer，對齊 skill::domain pattern)
- `backend/.../review/domain/package-info.java` (new — `@NamedInterface("domain")` 暴露 Review aggregate + events，對齊 skill::domain pattern)
- `backend/src/main/resources/db/migration/V11__create_notifications.sql` (modify — ref_event_id VARCHAR(36) → VARCHAR(255) 支援 composite ref event id 如 `<uuid>:<type>` / `<uuid>:<userId>:claim`)
- `backend/src/test/.../notification/NotificationProjectionListenerTest.java` (new — 6 ACs + insertSkill/insertRequest helpers)

**Deviations from task spec**：
1. **`SkillFlaggedEvent` 缺 flagId 欄位**：spec §4.4 範本假設 `e.flagId()` 存在；實際 record 為 `(aggregateId, type, description, reportedBy)`。改用 `aggregateId + ":" + type` 作 ref_event_id composite。副作用：同 skill 同 type 多筆 flag dedupe 為 1 通知（spam 防護 — owner 知道「skill X 有 spam 類回報」一次即可，逐筆 review 進 FlagsList）。**未改 SkillFlaggedEvent record**（避免改 security 模組 public signature 影響其他 caller）。
2. **`community.events` + `review.domain` 加 NamedInterface**：originally task 預期直接 `"community" / "review"` whole-module dep 即可訪問內部 event records；實測 Modulith 強制要求 sub-package 須 NamedInterface 暴露才能跨模組 import。Fix：兩個新 package-info.java with `@NamedInterface`，並把 notification 的 deps 改為 `community :: events / review :: domain`。對齊 skill::domain 既有 pattern。
3. **V11 ref_event_id 拓寬至 VARCHAR(255)**：原 VARCHAR(36) 假設純 UUID；composite ID 如 `<uuid>:<type>` 達 ~46 chars 撐爆。In-place edit V11（branch ahead of origin，無 production DB 已 apply 此 migration）— 比 add V12 ALTER 乾淨。
4. **3 條 trigger MVP 範圍**：spec §2.1 列「3 條 self-direct」但 routing table §2.4 列 4 個 listener；本 task 4 個全 ship（不額外 trim）— 範圍未變。

**Pattern echo**：
- **Sub-package events 走 `@NamedInterface` 暴露**：第 2 次驗證（首次 skill::domain，本 task 加 community::events / review::domain）— Modulith 邊界守則 effective，但需主動標 NamedInterface。
- **Composite ref_event_id**：listener 從 event payload 派生 deterministic key，UNIQUE constraint dedupe redelivery + 副作用 spam 防護。Pattern 適用所有「事件 record 缺穩定 unique id」的場景。
- **Modulith Scenario API**：`scenario.publish(event).andWaitForStateChange(...).andVerify(...)` — 驗證 AFTER_COMMIT async listener 標準路徑，對齊 SkillRatingProjectionListenerTest 既有 pattern。
