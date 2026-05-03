-- S096h2-T01: Notifications projection schema (per spec §4.2).
--
-- 兩 表 - notifications: per-recipient view + UNIQUE constraint 防 outbox redelivery
-- 重複 INSERT (AC-10 listener idempotency); notification_preferences: 4 categories
-- on/off + version 給 Spring Data JDBC isNew 區分。
--
-- partial index `idx_notifications_recipient_unread` 加速 unread-count query
-- (最高頻 path - AppShell bell badge 30s polling); 全 index for normal list paging.
--
-- version BIGINT NOT NULL DEFAULT 0 → factory new() 走 INSERT (version null 處理),
-- loaded entity .save() 走 UPDATE (version 非 null) - per Spring Data JDBC 慣例.

CREATE TABLE notifications (
    id              VARCHAR(36) PRIMARY KEY,
    recipient_id    VARCHAR(255) NOT NULL,
    category        VARCHAR(20)  NOT NULL CHECK (category IN ('flags','reviews','requests','versions')),
    title           TEXT         NOT NULL,
    body            TEXT,
    skill_id        VARCHAR(36),
    ref_event_id    VARCHAR(36)  NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (recipient_id, ref_event_id, category)
);
CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_id, created_at DESC)
    WHERE read_at IS NULL;
CREATE INDEX idx_notifications_recipient_all
    ON notifications (recipient_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id          VARCHAR(255) PRIMARY KEY,
    flags_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    reviews_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    requests_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    versions_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 0
);
