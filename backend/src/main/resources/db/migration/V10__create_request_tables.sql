-- S096g2-T01: requests + request_votes 表
--
-- requests: Request aggregate 持久化（ADR-002 充血聚合 + @Version 樂觀鎖）
-- request_votes: 1 user 1 vote per request 的 join table（UNIQUE 防 spam）
--
-- vote_count column 由 raw SQL atomic UPDATE 維護（per S076 download_count
-- pattern；T02 RequestVoteService 走 INSERT ON CONFLICT + UPDATE +1/-1）；
-- aggregate 內 @ReadOnlyProperty 防 save() 覆蓋並發 increment。

CREATE TABLE requests (
    id                  VARCHAR(36)  PRIMARY KEY,
    title               VARCHAR(200) NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    description         TEXT         NOT NULL CHECK (length(description) BETWEEN 1 AND 5000),
    requester_id        VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','FULFILLED')),
    claimer_id          VARCHAR(255),
    fulfilled_skill_id  VARCHAR(36)  REFERENCES skills(id) ON DELETE SET NULL,
    vote_count          BIGINT       NOT NULL DEFAULT 0 CHECK (vote_count >= 0),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_requests_status     ON requests (status);
CREATE INDEX idx_requests_votes_desc ON requests (vote_count DESC, created_at DESC);
CREATE INDEX idx_requests_created_desc ON requests (created_at DESC);

CREATE TABLE request_votes (
    request_id  VARCHAR(36)  NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    user_id     VARCHAR(255) NOT NULL,
    voted_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (request_id, user_id)
);
