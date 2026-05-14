-- S169 T01 — Allow EDITOR role in skill_grants.
-- V23 is S170 group tree principals; S169 continues at V24.

DO $$
DECLARE
    role_check_name text;
BEGIN
    SELECT con.conname
      INTO role_check_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey)
     WHERE rel.relname = 'skill_grants'
       AND con.contype = 'c'
       AND att.attname = 'role'
     LIMIT 1;

    IF role_check_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE skill_grants DROP CONSTRAINT %I', role_check_name);
    END IF;
END $$;

ALTER TABLE skill_grants
    ADD CONSTRAINT chk_skill_grants_role
    CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'));
