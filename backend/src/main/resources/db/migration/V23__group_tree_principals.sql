-- S170 T01 — Group tree principal model foundation.
-- V22 is already used by S156c; group tree starts at V23.

CREATE TABLE groups (
    id             VARCHAR(64) PRIMARY KEY,
    parent_id      VARCHAR(64) REFERENCES groups(id),
    kind           VARCHAR(24) NOT NULL,
    display_name   VARCHAR(160) NOT NULL CHECK (length(display_name) BETWEEN 1 AND 160),
    slug           VARCHAR(80) NOT NULL CHECK (length(slug) BETWEEN 1 AND 80),
    status         VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    sort_order     INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_groups_sibling_slug
    ON groups (COALESCE(parent_id, ''), slug);

CREATE INDEX idx_groups_parent_order
    ON groups (parent_id, sort_order, display_name)
    WHERE status = 'ACTIVE';

CREATE TABLE group_closure (
    ancestor_id    VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    descendant_id  VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    depth          INTEGER NOT NULL CHECK (depth >= 0),
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX idx_group_closure_descendant
    ON group_closure (descendant_id, ancestor_id, depth);

CREATE TABLE group_members (
    group_id       VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id        VARCHAR(64) NOT NULL REFERENCES users(id),
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_members_user
    ON group_members (user_id, group_id);
