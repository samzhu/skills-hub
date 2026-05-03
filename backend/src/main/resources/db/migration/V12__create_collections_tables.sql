-- V12: S096f2 Collections schema — collection aggregate + nested collection_skills
-- ----------------------------------------------------------------------------
-- per spec §4.2，新增 community 模組第二個 aggregate。
--
-- collection_skills PK 為 (collection_id, position) 而非 spec 範本的
-- (collection_id, skill_id) — 對齊 Spring Data JDBC @MappedCollection(keyColumn="position")
-- canonical pattern：position 是 list 索引、Spring 在 save() 時 delete-and-reinsert
-- 整個 list；若 PK 含 skill_id 則 child entity Optional<@Id> 與 keyColumn 衝突。
-- 改加 UNIQUE (collection_id, skill_id) 維持「同 collection 內 skill 不重複」語意，
-- 由 factory + DB 雙保護。
--
-- collections.version 為 @Version 樂觀鎖（對齊 Request S096g2 既驗 pattern）。
-- ----------------------------------------------------------------------------

CREATE TABLE collections (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    owner_id        VARCHAR(255) NOT NULL,
    category        VARCHAR(100) NOT NULL,
    install_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_collections_category ON collections (category);
CREATE INDEX idx_collections_created  ON collections (created_at DESC);
CREATE INDEX idx_collections_owner    ON collections (owner_id);

CREATE TABLE collection_skills (
    collection_id   VARCHAR(36) NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    skill_id        VARCHAR(36) NOT NULL,  -- soft FK：skill SUSPENDED 後仍保留歷史
    position        INTEGER     NOT NULL,
    PRIMARY KEY (collection_id, position),
    UNIQUE (collection_id, skill_id)
);

CREATE INDEX idx_collection_skills_skill ON collection_skills (skill_id);
