-- ============================================================================
-- S177 V26 — is_public-first search visibility
-- ============================================================================
--
-- Public visibility is now stored in skills.is_public and vector_store.is_public.
-- acl_entries only stores explicit user/group/company permissions.
-- LAB data is reset for this migration, so existing public/private values are
-- not preserved from the old generated column.
-- ============================================================================

DROP INDEX IF EXISTS idx_skills_is_public;
ALTER TABLE skills DROP COLUMN IF EXISTS is_public;
ALTER TABLE skills ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_skills_is_public
    ON skills (is_public) WHERE is_public = TRUE;

ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_vector_store_is_public
    ON vector_store (is_public) WHERE is_public = TRUE;

UPDATE skills
   SET acl_entries = acl_entries - 'public:*:read'
 WHERE acl_entries @> '["public:*:read"]'::jsonb;

UPDATE vector_store
   SET acl_entries = acl_entries - 'public:*:read'
 WHERE acl_entries @> '["public:*:read"]'::jsonb;
