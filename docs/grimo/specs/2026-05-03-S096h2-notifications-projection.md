# S096h2: Notifications Full Projection (community 三件套最後一件)

> Spec: S096h2 | Size: M(12) re-est from M(10-12) | Status: ⏳ Design
> Date: 2026-05-03

---

## 1. Goal

把 `/notifications` 從 stub 升級為從多個 domain events real-time projection 出每用戶的個人化通知列表 — Skill flagged / Review created / Request claimed / Request fulfilled 等事件觸發後，async listener 計算 recipient（基本上是 skill owner / request requester）、寫 `notifications` 表、前端鈴鐺 badge 與 NotificationsPage 即時顯示。前端 `/notifications` 頁有類別 filter / 已讀標記 / 刪除 / 訂閱偏好 4 個 mutation。Real-time 走 polling（已存在 30s 機制）；SSE / WebSocket defer。

**起源**：S096h1 ship 時明示 defer real projection / mutation endpoints / WebSocket eval 至本 spec。前端 NotificationsPage + AppShell 鈴鐺 badge UI 已就緒等填空。

**非目標**（本 spec 不做）：
- Version Diff page（roadmap row 原綁兩件，**剝離**至 S098c2/c3 處理）
- Broadcast notifications（vote 過某 request 被 fulfill 通知所有 voters / follow 某 skill 出新版通知 followers）— defer 為後續 polish
- SSE / WebSocket — defer，30s polling MVP 夠用
- 通知 grouping / digest（防 spam 大量同類通知）— defer

**Visual flow**：

```
事件發生                         例：bob 對 alice 的 skill 寫 review
                                 → ReviewCreatedEvent { reviewId, skillId, authorId, rating }
                                 → outbox publish (S023 既有)
                                 ⤷ async AFTER_COMMIT
NotificationProjectionListener  收到 ReviewCreatedEvent
  .onReviewCreated()            1. 查 skills.owner_id where id = skillId → alice
                                2. 查 notification_preferences where user_id = alice
                                   → reviews_enabled = TRUE? 是 → 繼續
                                3. INSERT notifications (recipient_id=alice,
                                   category=reviews, title="bob 對你的 skill X 寫了評論",
                                   body="...", skill_id=X, ref_event_id=...)

UI 反映                          alice 的瀏覽器（30s poll 已就緒）
                                 → GET /api/v1/notifications/unread-count → {count: 1}
                                 → AppShell 鈴鐺 badge 顯 1
                                 alice 點鈴鐺 → /notifications
                                 → GET /api/v1/notifications → list 含新通知

mark read                        alice 點通知 → POST /notifications/{id}/read
                                 → notifications.read_at = NOW()
                                 → 下次 unread-count 變 0
```

## 2. Approach

走 **CQRS + Event-driven projection** — 多個 `@ApplicationModuleListener` 訂閱跨 module events（flags / reviews / requests），各自映射到 notifications 表 INSERT。讀側走既有 polling pattern（30s `/unread-count`）+ Slice pagination 避 COUNT 災難。

### 2.1 7 個產品 / UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Subscription model | **per-user 4 categories on/off**（notification_preferences 表）+ MVP 不做 per-skill explicit follow | 介於完全 derived（每用戶設定 0）與 explicit follow（每 skill 操作）之間；defer follow 至 polish |
| 2 | Real-time delivery | **Polling 30s**（既有 AppShell pattern）；SSE/WebSocket eval defer | YAGNI；polling 已 ship，不破壞既有 UX；30s 對通知接受度足夠 |
| 3 | Trigger rules MVP | **3 條 self-direct only**（skill flag / skill review / request claim+fulfill）；vote/follow broadcast defer | 對 user 直接相關 = 高 signal；broadcast 易 spam |
| 4 | Read state model | **`read_at TIMESTAMPTZ NULL`**（含時間戳）取代既有 frontend type `read BOOLEAN` | timestamp 給未來「未讀最舊多久」分析空間；前端 `read = read_at != null` 一行 derive |
| 5 | Mutation endpoints | **4 個**：mark-single-read / mark-all-read / delete / update-preferences | 完整 CRUD；硬刪除（不開 archive） |
| 6 | Notifications schema | **新表 notifications**：per-recipient view，含 `ref_event_id` FK 到 `domain_events`（保留可 trace 回 source event） | 不重複 `domain_events`；FK 給 audit 保留 |
| 7 | Volume 防爆 | **MVP 不做 grouping/digest** | YAGNI；累積觀察到 spam 再加 |

### 2.2 Approach 比較 — Recipient 計算位置

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. Listener 內查 skill.owner_id / request.requester_id 算 recipient** | 邏輯集中在 notification 模組；event payload 不冗餘 | 跨 module query（需 `skill::domain` 等 dep） | ⭐ |
| B. Event payload 預先帶 recipient | 無跨 module query | event 設計被 notification 需求污染（producer 要知 consumer 想要啥）；違反 single-responsibility | |
| C. 事件發送方（如 ReviewService）算 recipient 並 publish 帶 recipient 的 event | 同上 | 同上 — 業務邏輯與通知策略耦合 | |

走 **A** — `notification` 模組 dep `skill::domain` + `community::domain` 拿 owner_id / requester_id。其他 producer 模組維持 event 純淨。

### 2.3 Modulith module 結構

新建 `notification` 模組（與 audit / analytics 同層），allowedDependencies：

```java
@ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {
        "shared :: events",          // ApplicationEventPublisher / DomainEvent
        "shared :: security",        // CurrentUserProvider for /me endpoints
        "shared :: api",             // Exception handling
        "skill :: domain",           // SkillRepository for owner_id lookup
        "skill :: security :: events",   // SkillFlaggedEvent (S058+)
        "review",                    // ReviewCreatedEvent etc (placement 由 implementer)
        "community"                  // RequestClaimedEvent / RequestFulfilledEvent
    }
)
package io.github.samzhu.skillshub.notification;
```

跨多 module 依賴算多但都是 read-only 訂閱 events / 查 read model。對齊 audit module 既有「跨模組訂閱者」pattern。

### 2.4 Listener routing table

| Source Event | Source Module | Recipient | Category | Title 樣板 |
|---|---|---|---|---|
| `SkillFlaggedEvent` | security | skill.owner_id | flags | 「你的技能 {skill.name} 被標記回報（{flag.type}）」 |
| `ReviewCreatedEvent` | review | skill.owner_id | reviews | 「{review.authorId} 對你的技能 {skill.name} 寫了 {rating}★ 評論」 |
| `RequestClaimedEvent` | community | request.requester_id | requests | 「{event.claimerId} 認領了你發起的需求 {request.title}」 |
| `RequestFulfilledEvent` | community | request.requester_id | requests | 「你發起的需求 {request.title} 已完成（skill: {fulfilled_skill.name}）」 |

每 event 一個 method on `NotificationProjectionListener`：

```java
@ApplicationModuleListener
public void onSkillFlagged(SkillFlaggedEvent e) {
    var skill = skillRepo.findById(e.skillId()).orElse(null);
    if (skill == null) return;  // skill 已刪除，skip
    if (!preferencesRepo.flagsEnabled(skill.getOwnerId())) return;
    notificationRepo.save(new Notification(
        UUID.randomUUID().toString(),
        skill.getOwnerId(),
        "flags",
        "你的技能 " + skill.getName() + " 被標記回報（" + e.type() + "）",
        e.description(),
        e.skillId(),
        e.flagId(),         // ref_event_id — 對應 domain_events row
        Instant.now(),
        null                // read_at
    ));
}
```

### 2.5 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| `@ApplicationModuleListener` AFTER_COMMIT async + outbox redelivery | Validated | S023/S024 既有 + S098e2 / AuditEventListener 範本 |
| Spring Data JDBC `notifications` 表 | Validated | 既有多 read model 同 pattern |
| Cross-module event subscription | Validated | audit module 已驗證跨模組訂閱 SkillCreated 等 events |
| Polling pattern | Validated | AppShell 鈴鐺 badge 既有 30s `/unread-count` 機制 |
| Listener idempotency（避 outbox redelivery 重複 INSERT） | **Hypothesis** | 用 `(recipient_id, ref_event_id, category)` UNIQUE constraint enforce；implementer 試 |

唯一 Hypothesis 為 idempotency 鍵的具體實作 — 試一輪即驗。**不需 POC**。

### 2.6 Trim list

M(12) 一個 cron tick 可能 wall hit；可 defer：

- **每 listener 的 category enable 預設值**（MVP 全部預設 ON）+ preferences UI 走最簡 4 toggle
- **Notification body 內含 actionable link**（點通知跳對應頁）— MVP 只顯 skillId 由前端 derive link，rich linking defer
- **Versions category**（skill 出新版通知）— 需 follow 機制，不在前 3 條 trigger rule，整 category defer
- **Collections category**（前端 type 沒此 enum，本 spec 不擴）

### 2.7 Research Citations

無外部框架研究。Internal references：

- `docs/grimo/specs/archive/2026-05-02-S096h1-notifications-stub.md`（前置 stub spec）
- `docs/grimo/PRD.md` §P9 (notifications)
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（projection pattern）
- `backend/.../notification/NotificationController.java`（既有 stub）
- `backend/.../audit/AuditEventListener.java`（跨模組訂閱範本）
- `backend/.../skill/SkillRatingProjectionListener.java` (S098e2)（async listener idempotent rebuild 範本）
- `frontend/src/pages/NotificationsPage.tsx`（既有 page shell + EmptyState）
- `frontend/src/components/AppShell.tsx`（既有 30s poll bell badge）
- `frontend/src/api/skills.ts:120-128`（`Notification` type 既有 — 加 `read_at` 欄位需 type 改 `read: boolean → readAt: string|null`）

## 3. SBE Acceptance Criteria

驗證指令：

- Backend：`./gradlew test` + `./gradlew modulithTest`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠

---

**AC-1：SkillFlagged → owner 收 notification**
- Given：alice 為 sk1 owner；alice 預設 flags_enabled=TRUE
- When：bob 對 sk1 發 POST flag
- Then：async ≤ 2s 後，alice 的 `GET /notifications` 含 1 筆 (category=flags, title 含「你的技能…被標記」, ref_event_id=該 SkillFlagged event id)；alice 的 `GET /notifications/unread-count` 回 `{count: 1}`

**AC-2：ReviewCreated → owner 收 notification**
- Given：alice 為 sk1 owner
- When：bob POST review (rating=5)
- Then：alice 收 1 筆 (category=reviews, title 含「bob 對你的技能…寫了 5★ 評論」)

**AC-3：RequestClaimed → requester 收 notification**
- Given：alice 發起 request r1
- When：bob POST claim r1
- Then：alice 收 1 筆 (category=requests, title 含「bob 認領了你發起的需求」)

**AC-4：RequestFulfilled → requester 收 notification**
- Given：alice 發起 r1；bob 已 claim
- When：bob POST fulfill r1 with skillId=sk5
- Then：alice 收 1 筆 (category=requests, title 含「你發起的需求…已完成」)

**AC-5：Preferences disabled → 不收 notification**
- Given：alice 設 reviews_enabled=FALSE
- When：bob 對 alice 的 sk1 寫 review
- Then：alice 不收新 notification；其他 categories 不影響

**AC-6：Mark-read endpoint**
- Given：alice 收 1 筆 unread notification (id=n1)
- When：alice POST `/notifications/n1/read`
- Then：回 204；DB `notifications.n1.read_at` = 當下 timestamp；`/unread-count` 變 `{count: 0}`

**AC-7：Mark-all-read endpoint**
- Given：alice 收 5 筆 unread
- When：alice POST `/notifications/read-all`
- Then：回 204；DB 5 筆 read_at 都填 timestamp；`/unread-count` 變 0

**AC-8：Delete endpoint**
- Given：alice 收 1 筆 notification (id=n1)
- When：alice DELETE `/notifications/n1`
- Then：回 204；DB row 消失（硬刪除）；list 不含 n1
- 非 owner（bob）DELETE → 403 not_notification_recipient

**AC-9：Preferences endpoint — update**
- Given：alice 預設全 enabled
- When：alice POST `/notifications/preferences` body `{flags: false, reviews: true, requests: true, versions: true}`
- Then：回 200；DB notification_preferences 對應 row UPDATE；下次 SkillFlagged 不會 INSERT for alice

**AC-10：Listener idempotency — 重複 event 不重複 INSERT**
- Given：SkillFlaggedEvent 觸發 listener；ACL projection insert 1 筆
- When：Modulith outbox redelivery 同 event id（模擬）
- Then：UNIQUE(recipient_id, ref_event_id, category) constraint 攔；DB 仍 1 筆；無 duplicate

**AC-11：Cross-module event 路由**
- Given：本 spec ship 後，notification 模組訂閱 4 個 source modules events
- When：跑 `./gradlew modulithTest`
- Then：所有 ModularityTests PASS；無循環依賴；notification module allowedDependencies 完整

**AC-12：Frontend NotificationsPage — 真資料 + filter chips + mark-read + delete**
- Given：alice 收 5 筆 notification (3 flags / 2 reviews) 全 unread
- When：user 開 `/notifications`
- Then：顯 5 筆 row，按 createdAt desc；上方 category filter chips (全部 / flags / reviews / requests)；點「全部已讀」5 筆變灰；點單筆右側 ✕ 確認後該筆消失

**AC-13：Frontend AppShell bell badge — 真 count + 即時更新**
- Given：alice 收新 notification
- When：30s 內輪詢觸發
- Then：bell badge 顯 unread count；點鈴鐺進 /notifications

**AC-14：Settings — preferences UI（4 toggle）**
- Given：alice 開 NotificationsPage 點「設定」按鈕（CTA 從 disabled 改 active）
- When：toggle 「flags」off → save
- Then：發 POST preferences；下次 SkillFlagged 不收

## 4. Interface / API Design

### 4.1 Backend — REST endpoints（取代既有 stub）

```
GET    /api/v1/notifications                        # 既有 stub → 真資料
   query ?category=flags|reviews|requests|versions (optional)
   200 [{ id, category, title, body, skillId, refEventId, readAt|null, createdAt }, ...]
   ※ Slice pagination — query ?cursor=<lastId>&limit=20 (no totalCount)

GET    /api/v1/notifications/unread-count           # 既有 stub → 真資料
   200 { count: number }

POST   /api/v1/notifications/{id}/read              # NEW
   204
   403 not_notification_recipient
   404 notification_not_found

POST   /api/v1/notifications/read-all               # NEW
   204

DELETE /api/v1/notifications/{id}                   # NEW (硬刪除)
   204
   403 not_notification_recipient
   404 notification_not_found

GET    /api/v1/notifications/preferences            # NEW (current user)
   200 { flags: boolean, reviews: boolean, requests: boolean, versions: boolean }

POST   /api/v1/notifications/preferences            # NEW
   body { flags?, reviews?, requests?, versions? }   # partial update
   200 { ...updated preferences }
```

### 4.2 Backend — Schema migrations

```sql
-- V<n>__create_notifications.sql
CREATE TABLE notifications (
    id              VARCHAR(36) PRIMARY KEY,
    recipient_id    VARCHAR(255) NOT NULL,
    category        VARCHAR(20) NOT NULL CHECK (category IN ('flags','reviews','requests','versions')),
    title           TEXT NOT NULL,
    body            TEXT,
    skill_id        VARCHAR(36),                       -- nullable; 指向相關 skill
    ref_event_id    VARCHAR(36) NOT NULL,              -- 對應 source domain_events row
    read_at         TIMESTAMPTZ,                       -- nullable = unread
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (recipient_id, ref_event_id, category)      -- listener idempotency 保護
);
CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_id, created_at DESC)
    WHERE read_at IS NULL;
CREATE INDEX idx_notifications_recipient_all
    ON notifications (recipient_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id         VARCHAR(255) PRIMARY KEY,
    flags_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    reviews_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    requests_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    versions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

UNIQUE constraint 防 outbox redelivery 重複 INSERT（AC-10）；partial index 加速「未讀」query（最高頻 path — 鈴鐺 unread-count 走這條）。

### 4.3 Backend — Notification Aggregate

```java
@Table("notifications")
public class Notification implements Persistable<String> {
    @Id String id;
    @Column("recipient_id") String recipientId;
    String category;
    String title;
    String body;
    @Column("skill_id") String skillId;
    @Column("ref_event_id") String refEventId;
    @Column("read_at") Instant readAt;
    @Column("created_at") Instant createdAt;

    public boolean isRead() { return readAt != null; }
    public void markRead() { this.readAt = Instant.now(); }
    public boolean isOwnedBy(String userId) { return Objects.equals(userId, recipientId); }
    @Override public boolean isNew() { return createdAt == null; }
    @Override public String getId() { return id; }
}
```

### 4.4 Backend — NotificationProjectionListener（核心）

```java
@Component
public class NotificationProjectionListener {
    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final SkillRepository skillRepo;
    private final RequestRepository requestRepo;

    @ApplicationModuleListener
    void onSkillFlagged(SkillFlaggedEvent e) {
        var skill = skillRepo.findById(e.skillId()).orElse(null);
        if (skill == null) return;
        var ownerId = skill.getOwnerId();
        if (!prefEnabled(ownerId, "flags")) return;
        save(ownerId, "flags",
            "你的技能 " + skill.getName() + " 被標記回報（" + e.type() + "）",
            e.description(), e.skillId(), e.flagId());
    }

    @ApplicationModuleListener
    void onReviewCreated(ReviewCreatedEvent e) {
        var skill = skillRepo.findById(e.skillId()).orElse(null);
        if (skill == null) return;
        var ownerId = skill.getOwnerId();
        if (Objects.equals(ownerId, e.authorId())) return;  // 不通知自己
        if (!prefEnabled(ownerId, "reviews")) return;
        save(ownerId, "reviews",
            e.authorId() + " 對你的技能 " + skill.getName() + " 寫了 " + e.rating() + "★ 評論",
            null, e.skillId(), e.reviewId());
    }

    @ApplicationModuleListener
    void onRequestClaimed(RequestClaimedEvent e) {
        var req = requestRepo.findById(e.requestId()).orElse(null);
        if (req == null) return;
        var requesterId = req.getRequesterId();
        if (Objects.equals(requesterId, e.claimerId())) return;
        if (!prefEnabled(requesterId, "requests")) return;
        save(requesterId, "requests",
            e.claimerId() + " 認領了你發起的需求 " + req.getTitle(),
            null, null, e.requestId());
    }

    @ApplicationModuleListener
    void onRequestFulfilled(RequestFulfilledEvent e) {
        var req = requestRepo.findById(e.requestId()).orElse(null);
        if (req == null) return;
        var requesterId = req.getRequesterId();
        if (!prefEnabled(requesterId, "requests")) return;
        var skill = skillRepo.findById(e.fulfilledSkillId()).orElse(null);
        save(requesterId, "requests",
            "你發起的需求 " + req.getTitle() + " 已完成"
                + (skill != null ? "（skill: " + skill.getName() + "）" : ""),
            null, e.fulfilledSkillId(), e.requestId());
    }

    private boolean prefEnabled(String userId, String category) {
        return prefRepo.findById(userId)
            .map(p -> switch (category) {
                case "flags" -> p.flagsEnabled();
                case "reviews" -> p.reviewsEnabled();
                case "requests" -> p.requestsEnabled();
                case "versions" -> p.versionsEnabled();
                default -> false;
            })
            .orElse(true);  // default ON if no preferences row
    }

    private void save(String recipient, String cat, String title, String body, String skillId, String refEventId) {
        try {
            notifRepo.save(new Notification(UUID.randomUUID().toString(), recipient, cat,
                title, body, skillId, refEventId, null, Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // UNIQUE(recipient_id, ref_event_id, category) — outbox redelivery 防護
        }
    }
}
```

### 4.5 Backend — NotificationService（mutations）

```java
@Service
public class NotificationService {
    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final CurrentUserProvider users;

    @Transactional
    public void markRead(String id) {
        var actor = users.current().userId();
        var n = notifRepo.findById(id).orElseThrow(NotificationNotFoundException::new);
        if (!n.isOwnedBy(actor)) throw new NotNotificationRecipientException();
        n.markRead();
        notifRepo.save(n);
    }

    @Transactional
    public void markAllRead() {
        var actor = users.current().userId();
        notifRepo.markAllReadForUser(actor, Instant.now());  // single UPDATE
    }

    @Transactional
    public void delete(String id) {
        var actor = users.current().userId();
        var n = notifRepo.findById(id).orElseThrow(NotificationNotFoundException::new);
        if (!n.isOwnedBy(actor)) throw new NotNotificationRecipientException();
        notifRepo.deleteById(id);
    }

    @Transactional
    public NotificationPreference updatePreferences(PreferenceUpdate update) {
        var actor = users.current().userId();
        var p = prefRepo.findById(actor).orElse(NotificationPreference.defaults(actor));
        update.apply(p);
        return prefRepo.save(p);
    }
}
```

### 4.6 Backend — NotificationQueryService（取代既有 stub）

```java
@Service
public class NotificationQueryService {
    private final NotificationRepository notifRepo;
    private final CurrentUserProvider users;

    public List<Notification> list(String category, String cursor, int limit) {
        var actor = users.current().userId();
        return notifRepo.findByRecipientWithCursor(actor, category, cursor, limit + 1);
        // returns limit+1 to derive hasNext (Slice pattern)
    }

    public long unreadCount() {
        var actor = users.current().userId();
        return notifRepo.countUnreadByRecipient(actor);
    }
}
```

### 4.7 Frontend — Type 改動

```typescript
// frontend/src/api/skills.ts (or new api/notifications.ts)

// 既有 Notification type 改 read → readAt：
export interface Notification {
  id: string
  category: 'flags' | 'reviews' | 'requests' | 'versions'
  title: string
  body: string | null
  skillId: string | null
  refEventId: string
  readAt: string | null   // CHANGED from `read: boolean`
  createdAt: string
}

export function fetchNotifications(category?: string, cursor?: string): Promise<Notification[]> { ... }
export function fetchUnreadCount(): Promise<{ count: number }> { ... }  // 既有 keep
export function markNotificationRead(id: string): Promise<void> { ... }
export function markAllNotificationsRead(): Promise<void> { ... }
export function deleteNotification(id: string): Promise<void> { ... }

export interface NotificationPreferences {
  flags: boolean
  reviews: boolean
  requests: boolean
  versions: boolean
}

export function fetchPreferences(): Promise<NotificationPreferences> { ... }
export function updatePreferences(p: Partial<NotificationPreferences>): Promise<NotificationPreferences> { ... }
```

### 4.8 Frontend — NotificationsPage 改寫

```tsx
// frontend/src/pages/NotificationsPage.tsx
// - hero 加 category filter chips (全部 / flags / reviews / requests)
// - 取代 EmptyState 為 list（有 0 筆時退回 EmptyState）
// - row: title + body + createdAt + 已讀/未讀視覺 + ✕ delete button
// - 「全部已讀」hero button (POST read-all)
// - 「設定」按鈕（既有 disabled） → enable 開 PreferencesModal

function NotificationRow({ n }: { n: Notification }) {
  const isRead = n.readAt !== null
  return (
    <div className={isRead ? 'opacity-60' : ''} onClick={() => mutateRead(n.id)}>
      <CategoryDot category={n.category} />
      <span>{n.title}</span>
      {n.body && <p className="text-muted">{n.body}</p>}
      <time>{formatRelativeTime(n.createdAt)}</time>
      <button onClick={(e) => { e.stopPropagation(); mutateDelete(n.id); }}>✕</button>
    </div>
  )
}

function PreferencesModal({ open, onClose }) {
  // 4 toggles (flags / reviews / requests / versions)
  // Submit → POST /preferences
}
```

### 4.9 Frontend — AppShell bell badge

既有 30s poll 已就緒，無需改。`/unread-count` endpoint 從 stub 變真實，badge 自然顯真 count。

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../notification/package-info.java` | new | `@ApplicationModule(displayName="Notifications", allowedDependencies={...})` |
| `backend/.../notification/Notification.java` | new | Aggregate |
| `backend/.../notification/NotificationRepository.java` | new | Spring Data JDBC repo + cursor pagination + countUnread + markAllReadForUser |
| `backend/.../notification/NotificationPreference.java` | new | Aggregate (4 boolean fields) |
| `backend/.../notification/NotificationPreferenceRepository.java` | new | Spring Data JDBC repo |
| `backend/.../notification/NotificationService.java` | new | mark-read / read-all / delete / update-preferences |
| `backend/.../notification/NotificationQueryService.java` | new | list with cursor / unreadCount |
| `backend/.../notification/NotificationController.java` | modify (取代 stub) | 6 endpoints (GET list/unread-count/preferences + POST read/read-all/preferences + DELETE) |
| `backend/.../notification/NotificationProjectionListener.java` | new | 4 `@ApplicationModuleListener` 訂閱 events |
| `backend/.../notification/NotificationNotFoundException.java` etc | new | + GlobalExceptionHandler mapping |
| `backend/src/main/resources/db/migration/V<n>__create_notifications.sql` | new | 見 §4.2 |
| `backend/src/test/.../notification/NotificationProjectionListenerTest.java` | new | AC-1/2/3/4/5/10 (Testcontainers + cross-module event publishing) |
| `backend/src/test/.../notification/NotificationServiceTest.java` | new | AC-6/7/8/9 |
| `backend/src/test/.../notification/NotificationControllerTest.java` | new | web slice — 6 endpoints |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/notifications.ts` | new (split from skills.ts) | type + 7 fetcher functions |
| `frontend/src/api/skills.ts` | modify | 移除舊 Notification type / fetchNotifications / fetchUnreadCount |
| `frontend/src/hooks/useNotifications.ts` | new | TanStack Query list with category filter |
| `frontend/src/hooks/useNotificationPreferences.ts` | new | get + update mutation |
| `frontend/src/pages/NotificationsPage.tsx` | modify (取代 EmptyState 為主邏輯) | 加 filter chips + list + mark-read + delete + 「設定」CTA enable + Preferences modal trigger |
| `frontend/src/components/PreferencesModal.tsx` | new (or inline if implementer 視 testability) | 4 toggle 表單 + submit |
| `frontend/src/components/AppShell.tsx` | (no change required) | 既有 30s poll 自動接真資料 |
| `frontend/src/pages/NotificationsPage.test.tsx` | new | AC-12 / AC-14 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M90h2 row：📋 → 📐 in-design + scope 改 only Notifications + 估點 M(12) + 設計摘要；Version Diff scope 從 row 移除（澄清歸 S098c2/c3）|
| `docs/grimo/glossary.md` | modify | 加 Notification / Preference / Subscription 中英對照 |
| `docs/grimo/architecture.md` | modify | notification module 加進 module map |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
