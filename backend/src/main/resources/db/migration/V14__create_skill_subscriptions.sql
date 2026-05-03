-- V14: S125a SkillSubscription schema — user-skill 關注關係 (per PRD P9 / glossary)
-- ----------------------------------------------------------------------------
-- 加 community 模組第三個 aggregate（Collection / Request 之後）。
--
-- 為新版發布通知（S125b NotificationProjectionListener.onVersionPublished）提供
-- subscriber lookup 表。LAB 封測前 user-visible flow 補完：
--   "Given 使用者訂閱了 docker-compose-helper skill
--    When 該 skill 作者發布 v2.1.0
--    Then 使用者通知中心顯示 1 unread badge" (per PRD §285-§291 P9 SBE scenario 1)
--
-- 設計決策：
-- - 純 join-like aggregate；無 mutable state（subscribe/unsubscribe = save/delete row）
-- - PK = id (UUID) 對齊既有 community aggregate（Collection / Request）pattern
-- - UNIQUE(skill_id, subscriber_id) 防同 user 對同 skill 重複訂閱
-- - skill_id soft-FK（不加 ON DELETE CASCADE）— 對齊 collection_skills 既驗：
--   skill SUSPENDED / 後續操作不影響 subscription history（per audit invariant）
-- - subscriber_id 對應 JWT sub claim (S115 既驗)；no FK 因為 user 表不存在
-- ----------------------------------------------------------------------------

CREATE TABLE skill_subscriptions (
    id              VARCHAR(36)  PRIMARY KEY,
    skill_id        VARCHAR(36)  NOT NULL,
    subscriber_id   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (skill_id, subscriber_id)
);

CREATE INDEX idx_skill_subscriptions_skill_id      ON skill_subscriptions (skill_id);
CREATE INDEX idx_skill_subscriptions_subscriber_id ON skill_subscriptions (subscriber_id);
