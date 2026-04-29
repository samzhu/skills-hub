-- ============================================================================
-- S023 V4 — Spring Modulith Event Publication Registry (transactional outbox)
-- ============================================================================
--
-- ADR-002 § implementation phase 1：啟用 transactional outbox pattern
-- （@ApplicationModuleListener 可靠投遞 + 失敗 retry）
--
-- 與 domain_events 表的分工：
--   domain_events    = 業務 audit log（permanent；S024 後由 AuditEventListener 寫入；
--                      S023 階段仍為 source of truth — transitional state）
--   event_publication = Modulith outbox（投遞狀態追蹤；可 archive 或 cleanup）
--
-- DDL 來源：spring-modulith-events-jdbc 2.0.6 v2 schema (PostgreSQL)
--   https://github.com/spring-projects/spring-modulith/blob/main/
--     spring-modulith-events/spring-modulith-events-jdbc/
--     src/main/resources/org/springframework/modulith/events/jdbc/schemas/
--     v2/schema-postgresql.sql
--
-- 關聯設定（application.yaml）：
--   spring.modulith.events.jdbc.schema-initialization.enabled = false
--     → 由本 Flyway migration 管，避免 starter auto-init 衝突
-- ============================================================================

CREATE TABLE IF NOT EXISTS event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- hash index 用於 listener 查詢完成狀態（by serialized_event content）
-- 注意：當 serialized_event 超過 8191 bytes 時此 index 會阻擋 INSERT
-- （per spring-modulith issue #519；deepwiki design-decisions §3 陷阱 10）
-- 對策：Skills Hub domain events 只序列化 ID + 關鍵欄位，不含 SKILL.md / SARIF
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

-- 用於查詢 completion_date IS NULL（incomplete publications）+ 清理已完成 row
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);

-- ----------------------------------------------------------------------------
-- event_publication_archive — 選用 archive 表
-- 對應 spring.modulith.events.completion-mode=archive 設定（本 spec 未啟用，
-- 但表先建好以便未來啟用無需新 migration）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS event_publication_archive
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_archive_serialized_event_hash_idx
    ON event_publication_archive USING hash (serialized_event);

CREATE INDEX IF NOT EXISTS event_publication_archive_by_completion_date_idx
    ON event_publication_archive (completion_date);

-- ============================================================================
-- AnalyticsProjection 冪等保護：download_events.event_id UNIQUE
-- T03 實作 @ApplicationModuleListener 重投時，透過 ON CONFLICT 防重複行
-- 既有 row 預設用 random UUID（gen_random_uuid）填入，不影響歷史資料
-- ============================================================================

ALTER TABLE download_events
    ADD COLUMN IF NOT EXISTS event_id VARCHAR(36) NOT NULL DEFAULT gen_random_uuid()::text;

CREATE UNIQUE INDEX IF NOT EXISTS uq_download_events_event_id
    ON download_events (event_id);
