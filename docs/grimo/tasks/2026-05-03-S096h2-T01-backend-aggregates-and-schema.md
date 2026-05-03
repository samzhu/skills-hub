# S096h2-T01: Backend Notification + NotificationPreference aggregates + V11 schema + module wiring

## Spec
S096h2 — Notifications Full Projection（spec doc: `docs/grimo/specs/2026-05-03-S096h2-notifications-projection.md`）

## BDD（涵蓋的 AC — infrastructure base，T02/T03 跑跨 listener/service test 才會綠）

**AC-11: Modulith 邊界驗證 — notification 模組正式註冊**
- Given：本 task ship 後 notification 模組正式 `@ApplicationModule(allowedDependencies = {shared::events, shared::security, shared::api, skill::domain, security::events, review, community})`
- When：跑 `./gradlew modulithTest`
- Then：所有 ModularityTests PASS（無循環依賴；6 個 cross-module dep 都在 allowed list）

**AC-Schema: V11 migration 安全 apply**
- Given：DB clean state + V10 ship 完
- When：`./gradlew bootTestRun` Flyway run
- Then：`notifications` 表 + `notification_preferences` 表建好，UNIQUE(recipient_id, ref_event_id, category) constraint 存在，2 個 partial/full index 存在；`./gradlew test` 全綠 + Notification aggregate 可 INSERT/findById round-trip

**AC-Aggregate: Notification.markRead + ownership check**
- Given：Notification 物件 read_at=null，recipient_id="alice"
- When：alice 呼叫 isOwnedBy("alice") + markRead()
- Then：isOwnedBy true / read_at != null；isOwnedBy("bob") = false

## Implementation outline

### `backend/src/main/resources/db/migration/V11__create_notifications.sql` (new)

```sql
CREATE TABLE notifications (
    id              VARCHAR(36) PRIMARY KEY,
    recipient_id    VARCHAR(255) NOT NULL,
    category        VARCHAR(20) NOT NULL CHECK (category IN ('flags','reviews','requests','versions')),
    title           TEXT NOT NULL,
    body            TEXT,
    skill_id        VARCHAR(36),
    ref_event_id    VARCHAR(36) NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (recipient_id, ref_event_id, category)
);
CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_id, created_at DESC)
    WHERE read_at IS NULL;
CREATE INDEX idx_notifications_recipient_all
    ON notifications (recipient_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id          VARCHAR(255) PRIMARY KEY,
    flags_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    reviews_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    requests_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    versions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### `backend/.../notification/Notification.java` (new)

- `@Table("notifications")` + `Persistable<String>` (因 @Id 自手動指定 UUID)
- 9 fields per spec §4.3
- `markRead()` mutate `read_at = Instant.now()`
- `isOwnedBy(userId)` + `isRead()` derive
- `isNew()` 用 `createdAt == null`（新建時 createdAt 由 application 填）

### `backend/.../notification/NotificationPreference.java` (new)

- `@Table("notification_preferences")` + Persistable<String>
- 4 boolean fields + `defaults(userId)` static factory（全 TRUE）
- 對應 enum lookup helper（給 listener prefEnabled 切 switch 時讓 caller 不重複）

### `backend/.../notification/NotificationRepository.java` (new) + `NotificationPreferenceRepository.java` (new)

`NotificationRepository`：
- `Page<Notification> findByRecipientIdAndOptionalCategory` (cursor pagination — `@Query` ORDER BY created_at DESC + LIMIT)
- `long countByRecipientIdAndReadAtIsNull(String recipientId)`
- `@Modifying @Query int markAllReadForUser(String userId, Instant ts)`

### `backend/.../notification/package-info.java` (new)

```java
@ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {
        "shared :: events",
        "shared :: security",
        "shared :: api",
        "skill :: domain",
        "security :: events",
        "review",
        "community"
    }
)
package io.github.samzhu.skillshub.notification;
```

### Tests `backend/src/test/.../notification/NotificationModuleSmokeTest.java` (new)

- ApplicationContext loads PASS
- Notification.markRead state transition
- NotificationRepository basic INSERT/findById
- NotificationPreferenceRepository default fall-through

## Target Files

- `backend/src/main/resources/db/migration/V11__create_notifications.sql` (new)
- `backend/.../notification/Notification.java` (new — Persistable)
- `backend/.../notification/NotificationPreference.java` (new — Persistable)
- `backend/.../notification/NotificationRepository.java` (new)
- `backend/.../notification/NotificationPreferenceRepository.java` (new)
- `backend/.../notification/package-info.java` (new — @ApplicationModule)
- `backend/src/test/.../notification/NotificationModuleSmokeTest.java` (new — Testcontainers smoke)

## Depends On
- S096g2 ship ✅（community module 已正式註冊；community::domain RequestRepository 可訂閱）
- S098e2 ship ✅（review module 已正式註冊；ReviewCreatedEvent 已存在）
- S072+ FlagService + SkillFlaggedEvent ✅（security::events 既有）

## Status
pending
