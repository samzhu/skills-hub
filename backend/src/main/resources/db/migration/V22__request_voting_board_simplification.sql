-- S156c voting-board pivot — drop claim/release/fulfill state machine columns + add request_comments.
-- 兩段：T01 拆 columns；T02 加 request_comments table（同 spec §2.3 / §2.6 設計）。

-- 1. drop claim/fulfill columns + status index (T01)
DROP INDEX IF EXISTS idx_requests_status;
ALTER TABLE requests DROP COLUMN IF EXISTS status;
ALTER TABLE requests DROP COLUMN IF EXISTS claimer_id;
ALTER TABLE requests DROP COLUMN IF EXISTS fulfilled_skill_id;

-- 2. add request_comments table (T02) — soft delete pattern；CASCADE on request hard delete
-- version column 為 Spring Data JDBC @Version optimistic lock + INSERT/UPDATE 區分器
-- （per codebase 既驗 Collection / Request pattern；無 version field 走 save() 會被當 UPDATE，silent no-op）
CREATE TABLE request_comments (
    id          VARCHAR(36)  PRIMARY KEY,
    request_id  VARCHAR(36)  NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    author_id   VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL CHECK (length(content) BETWEEN 1 AND 5000),
    created_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0
);

-- 查詢主路徑：findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc
-- (request_id, created_at) composite covers 排序 + filter；deleted_at 過濾走 partial index 不划算 (MVP)
CREATE INDEX idx_request_comments_request ON request_comments(request_id, created_at);
