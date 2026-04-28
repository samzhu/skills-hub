-- ============================================================================
-- S016 V2 — Row-Level ACL 基礎建設（skills + vector_store）
-- ============================================================================
--
-- 範圍：
--   1. skills.acl_entries JSONB NOT NULL DEFAULT '[]'
--   2. vector_store.acl_entries JSONB NOT NULL DEFAULT '[]'
--   3. GIN index × 2（默認 jsonb_ops — 必要：jsonb_path_ops 不支援 ?| / ?
--      operator，per spec §2.4 #1 + PostgreSQL 16 docs）
--   4. backfill：
--      - skills：author 即 owner（read+write+delete）
--      - vector_store：owner NOT NULL → user:<owner>:read；NULL 維持 [] (fail-secure)
--
-- 設計依據：
--   - ADR-001 §3.1：ACL JSONB inline 為 deliberate 選擇，否決 RLS / Spring Security ACL 模組
--   - spec §2.4 #1：reference repo samzhu/spring-acl-jsonb 用 jsonb_path_ops 為隱性 BUG
--     （?| operator 在 jsonb_path_ops 上 planner 永遠走 seq scan）
--   - WHERE acl_entries = '[]'::jsonb 條件確保 idempotent 重跑 migration 安全
-- ============================================================================

-- 1. skills.acl_entries
ALTER TABLE skills
    ADD COLUMN acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_skills_acl_entries
    ON skills USING GIN (acl_entries);   -- default jsonb_ops；支援 ?| / ?

-- 2. skills backfill — author 即 owner（MVP 簡化模型）
UPDATE skills
SET acl_entries = jsonb_build_array(
    'user:' || author || ':read',
    'user:' || author || ':write',
    'user:' || author || ':delete'
)
WHERE acl_entries = '[]'::jsonb;

-- 3. vector_store.acl_entries
ALTER TABLE vector_store
    ADD COLUMN acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_vector_store_acl_entries
    ON vector_store USING GIN (acl_entries);

-- 4. vector_store backfill — owner=NULL 的 row 維持 '[]'（fail-secure）
UPDATE vector_store
SET acl_entries = jsonb_build_array('user:' || owner || ':read')
WHERE owner IS NOT NULL
  AND acl_entries = '[]'::jsonb;
