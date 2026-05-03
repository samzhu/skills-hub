-- S098e2-T01: reviews 表 — Review aggregate 持久化（ADR-002 充血聚合）
--
-- 業務不變量：
--   * rating ∈ [1, 5]（CHECK 防 application bug）
--   * content 長度 1-2000 字元（CHECK 防直接 SQL bypass aggregate validation）
--   * (skill_id, author_id) UNIQUE — AC-4 1-per-user 終局守門
--   * skill_id ON DELETE CASCADE — Skill 刪除時 review 隨之清理
--
-- T02 將 ALTER TABLE skills ADD COLUMN average_rating + review_count
-- 並透過 SkillRatingProjectionListener AFTER_COMMIT 訂閱 ReviewCreatedEvent /
-- ReviewDeletedEvent 維護 projection。

CREATE TABLE reviews (
    id           VARCHAR(36)  PRIMARY KEY,
    skill_id     VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    author_id    VARCHAR(255) NOT NULL,
    rating       INT          NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content      TEXT         NOT NULL CHECK (length(content) BETWEEN 1 AND 2000),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (skill_id, author_id)
);

CREATE INDEX idx_reviews_skill_created_desc ON reviews (skill_id, created_at DESC);
