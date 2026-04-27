-- ============================================================================
-- S014 V1 initial schema — Skills Hub PostgreSQL data layer + pgvector store
-- ============================================================================
--
-- 範圍：
--   1. PostgreSQL extensions: vector (pgvector), uuid-ossp
--   2. 5 個業務表：domain_events / skills / skill_versions / flags /
--      download_events
--   3. vector_store 表（Spring AI 官方 PgVectorStore 預期 schema 加自訂
--      owner / skill_id 欄位 — S015 才接管寫入；S017 才用 ACL filter）
--   4. 對應 indexes（含 vector_store HNSW index）
--
-- 所有業務 id 欄位採 VARCHAR(36) 存 UUID 字串，與既有 SkillReadModel
-- 的 String id 一致，遷移時不需 type converter。
--
-- vector_store id 欄位採 PostgreSQL 原生 UUID 型別，對齊 Spring AI
-- 官方 PgVectorStore 的預設行為（DEFAULT_ID_TYPE = PgIdType.UUID）。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ----------------------------------------------------------------------------
-- 1. domain_events — Event Store（核心域 ES + CQRS 寫入端）
-- ----------------------------------------------------------------------------
CREATE TABLE domain_events (
    id              VARCHAR(36)  PRIMARY KEY,
    aggregate_id    VARCHAR(36)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sequence        BIGINT       NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb
);

-- (aggregate_id, sequence) UNIQUE — Aggregate 樂觀鎖、防 sequence 重複
CREATE UNIQUE INDEX idx_domain_events_aggregate_seq
    ON domain_events (aggregate_id, sequence);

-- ----------------------------------------------------------------------------
-- 2. skills — SkillReadModel（CQRS 查詢端 read model）
-- ----------------------------------------------------------------------------
CREATE TABLE skills (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL UNIQUE,
    description     TEXT,
    author          VARCHAR(255) NOT NULL,
    category        VARCHAR(50),
    latest_version  VARCHAR(20),
    risk_level      VARCHAR(10),
    status          VARCHAR(20)  NOT NULL,
    download_count  BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_skills_category ON skills (category);
CREATE INDEX idx_skills_status   ON skills (status);

-- ----------------------------------------------------------------------------
-- 3. skill_versions — SkillVersionReadModel
-- ----------------------------------------------------------------------------
CREATE TABLE skill_versions (
    id                VARCHAR(36)  PRIMARY KEY,
    skill_id          VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version           VARCHAR(20)  NOT NULL,
    storage_path      VARCHAR(500) NOT NULL,
    file_size         BIGINT       NOT NULL,
    frontmatter       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    risk_assessment   JSONB,
    published_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (skill_id, version)
);

-- 既有用法：findBySkillIdOrderByPublishedAtDesc（S001 至今）
CREATE INDEX idx_skill_versions_skill_published
    ON skill_versions (skill_id, published_at DESC);

-- ----------------------------------------------------------------------------
-- 4. flags — FlagReadModel
-- ----------------------------------------------------------------------------
CREATE TABLE flags (
    id           VARCHAR(36)  PRIMARY KEY,
    skill_id     VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    type         VARCHAR(20)  NOT NULL,
    description  TEXT,
    reported_by  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
);

CREATE INDEX idx_flags_skill ON flags (skill_id);

-- ----------------------------------------------------------------------------
-- 5. download_events — DownloadEventReadModel
-- ----------------------------------------------------------------------------
CREATE TABLE download_events (
    id             VARCHAR(36)  PRIMARY KEY,
    skill_id       VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version        VARCHAR(20)  NOT NULL,
    downloaded_at  TIMESTAMPTZ  NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_download_events_skill_time
    ON download_events (skill_id, downloaded_at DESC);

-- ----------------------------------------------------------------------------
-- 6. vector_store — Spring AI 官方 PgVectorStore + 自訂 owner / skill_id 欄位
-- ----------------------------------------------------------------------------
-- 設計依據（S014 §2.4 Challenge #13，已驗證自官方 PgVectorStore.java 原始碼）：
--   - 官方 INSERT SQL: INSERT INTO vector_store (id, content, metadata, embedding)
--                      VALUES (?, ?, ?::jsonb, ?)
--                      ON CONFLICT (id) DO UPDATE SET content=?, metadata=?::jsonb, embedding=?
--     → 多餘欄位（owner / skill_id）首次 INSERT 為 NULL；upsert 不動它們
--   - 官方 SELECT 用 SELECT * — 多欄位無害
--   - schema_validation 預設 false — 多欄位不會 fail starter
--
-- S014 階段 vector_store 表已建好但未啟用：
--   - SearchConfig 仍透過 @ConditionalOnProperty 走 firestore / simple
--   - PgVectorStore auto-config bean 因 @ConditionalOnMissingBean(VectorStore.class) 不建立
--   - vector_store 表 SELECT COUNT(*) = 0
--
-- S015 接管時：在 SearchConfig 加 @ConditionalOnProperty(... havingValue="pgvector")
-- bean，並在 SearchProjection 寫入流程加 owner 寫入策略（兩步驟 add + UPDATE 或自寫 INSERT）
-- ----------------------------------------------------------------------------
CREATE TABLE vector_store (
    id          UUID         DEFAULT uuid_generate_v4() PRIMARY KEY,
    content     TEXT,
    metadata    JSON,                                    -- Spring AI 預設用 json（INSERT 時 ::jsonb cast）
    embedding   VECTOR(768),                             -- Gemini text-embedding-2 = 768 dims
    owner       VARCHAR(255),                            -- ★ S015 寫入；S016 用於 ACL 授權
    skill_id    VARCHAR(36)                              -- ★ 反向關聯 skills(id)
                REFERENCES skills(id) ON DELETE CASCADE
);

-- HNSW index — 對齊 application.yaml 的 spring.ai.vectorstore.pgvector.index-type=HNSW
CREATE INDEX vs_emb_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);

-- owner 索引 — S016 ACL 階段才會用上（粗略 owner-only 過濾；
-- S016 加 acl_entries JSONB + GIN 後會以 acl_entries ?| ARRAY[...] 為主路徑）
CREATE INDEX idx_vector_store_owner
    ON vector_store (owner);
