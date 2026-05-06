-- ============================================================================
-- S114a V17 — Backfill skill_grants from existing acl_entries + format migration
-- ============================================================================
--
-- 範圍：
--   1. 將 acl_entries 中舊格式 "*:read" / "public:read" 統一換成 "public:*:read"
--   2. 從 acl_entries 反推 OWNER grants（有 delete 權限 → OWNER）
--   3. 從 acl_entries 建立 public VIEWER grants（含 "public:*:read"）
--
-- 設計依據：
--   - spec §2.5 Migration backfill
--   - 步驟 1 必須先跑，才能讓 V16 的 is_public generated column 正確計算
--   - ON CONFLICT DO NOTHING 確保 idempotent 重跑安全
-- ============================================================================

-- 1. Convert legacy 2-segment "*:read" / "public:read" → "public:*:read"
--    acl_entries 為 JSONB array of string；逐元素替換舊格式字串
UPDATE skills
SET acl_entries = (
    SELECT COALESCE(
        jsonb_agg(
            CASE
                WHEN entry IN ('*:read', 'public:read') THEN '"public:*:read"'::jsonb
                ELSE to_jsonb(entry)
            END
            ORDER BY entry   -- stable sort 確保 output deterministic（idempotency）
        ),
        '[]'::jsonb
    )
    FROM jsonb_array_elements_text(acl_entries) AS entry
)
WHERE acl_entries @> '["*:read"]'::jsonb
   OR acl_entries @> '["public:read"]'::jsonb;

-- 2. Backfill OWNER grants from acl_entries
--    邏輯：同一 (principal_type, principal) pair 含 "delete" permission → OWNER；否則 VIEWER
INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at)
SELECT
    gen_random_uuid()::text,
    s.id,
    parsed.ptype,
    parsed.principal,
    CASE WHEN 'delete' = ANY(parsed.perms) THEN 'OWNER' ELSE 'VIEWER' END AS role,
    COALESCE(s.author, 'system')   AS granted_by,
    s.created_at
FROM skills s,
     LATERAL (
         SELECT
             split_part(entry, ':', 1)                        AS ptype,
             split_part(entry, ':', 2)                        AS principal,
             array_agg(DISTINCT split_part(entry, ':', 3))    AS perms
         FROM   jsonb_array_elements_text(s.acl_entries) AS entry
         WHERE  split_part(entry, ':', 1) IN ('user', 'group', 'company')
           AND  entry <> 'public:*:read'
         GROUP BY split_part(entry, ':', 1), split_part(entry, ':', 2)
     ) parsed
ON CONFLICT ON CONSTRAINT uq_skill_grants_principal DO NOTHING;

-- 3. Backfill public VIEWER grants（含 "public:*:read" 的 skill）
INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at)
SELECT
    gen_random_uuid()::text,
    s.id,
    'public',
    '*',
    'VIEWER',
    COALESCE(s.author, 'system'),
    s.created_at
FROM skills s
WHERE s.acl_entries @> '["public:*:read"]'::jsonb
ON CONFLICT ON CONSTRAINT uq_skill_grants_principal DO NOTHING;
