-- S018 T5: SKILL.md 對齊 — allowed_tools 升 first-class column
-- (V2 已被 S016 占用 acl_entries；本 migration 用 V3)

-- 1. skill_versions 加 allowed_tools JSONB column（用 JSONB 與既有 acl_entries 同模式，
--    可重用 StringListJsonbConverter；原 spec §4.8 寫 TEXT[]，per S017 spec drift 慣例
--    inline 改為 JSONB — Spring Data JDBC 對 JSONB 的 converter 支援更穩健，且
--    後續若需 query allowed-tools subset，JSONB ?| operator + GIN index 與 acl_entries
--    模式一致）
ALTER TABLE skill_versions
    ADD COLUMN allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 2. backfill 從既有 frontmatter JSONB 解析 allowed-tools（space-separated string）
--    既有 row 若 frontmatter 含 allowed-tools 字串，split 成 array；無則保持 default '[]'
UPDATE skill_versions
SET allowed_tools = (
    SELECT COALESCE(jsonb_agg(t), '[]'::jsonb)
    FROM unnest(string_to_array(frontmatter->>'allowed-tools', ' ')) AS t
)
WHERE frontmatter ? 'allowed-tools'
  AND frontmatter->>'allowed-tools' IS NOT NULL
  AND frontmatter->>'allowed-tools' != '';
