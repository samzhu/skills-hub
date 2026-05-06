-- ============================================================================
-- S114a V16 — RBAC ACL Materialized Projection schema
-- ============================================================================
--
-- 範圍：
--   1. skills.owner_id VARCHAR(255) NOT NULL（backfill from author）
--   2. skills.is_public BOOLEAN GENERATED（acl_entries @> '["public:*:read"]'）
--   3. skill_grants 新表（source-of-truth grant rows）
--
-- 設計依據：
--   - spec §4.2 + §2.1 Approach A（CQRS-lite + Materialized Projection）
--   - is_public 用 3-segment "public:*:read" — 與 V17 backfill 格式一致
--   - skill_grants.UNIQUE(skill_id, principal_type, principal_id) 防 duplicate grants
-- ============================================================================

-- 1. owner_id — 從 author backfill；既有 skill 全數 backfill 後設 NOT NULL
ALTER TABLE skills ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
UPDATE skills SET owner_id = COALESCE(author, 'unknown') WHERE owner_id IS NULL;
ALTER TABLE skills ALTER COLUMN owner_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_skills_owner ON skills (owner_id);

-- 2. is_public generated column — STORED 因 GIN index 不支援 virtual generated；
--    值從 acl_entries 自動計算（無需應用層同步）；V17 backfill 先轉換 *:read → public:*:read
ALTER TABLE skills ADD COLUMN IF NOT EXISTS is_public BOOLEAN
    GENERATED ALWAYS AS (acl_entries @> '["public:*:read"]'::jsonb) STORED;
CREATE INDEX IF NOT EXISTS idx_skills_is_public ON skills (is_public) WHERE is_public = TRUE;

-- 3. skill_grants table
CREATE TABLE IF NOT EXISTS skill_grants (
    id              VARCHAR(36)  PRIMARY KEY,
    skill_id        VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    principal_type  VARCHAR(20)  NOT NULL CHECK (principal_type IN ('user','group','company','public')),
    principal_id    VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER','VIEWER')),
    granted_by      VARCHAR(255) NOT NULL,
    granted_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_skill_grants_principal UNIQUE (skill_id, principal_type, principal_id)
);
CREATE INDEX IF NOT EXISTS idx_grants_skill      ON skill_grants (skill_id);
CREATE INDEX IF NOT EXISTS idx_grants_principal  ON skill_grants (principal_type, principal_id);
