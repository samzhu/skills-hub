-- S070 (M66, v2.48.0): cleanup pre-S033 SUSPENDED vector_store orphans
--
-- Background:
--   S033 (M29 v2.10.0, 2026-05-01) added SearchProjection.onSkillSuspended
--   listener that DELETEs vector_store row when a skill becomes SUSPENDED.
--   But events that fired BEFORE S033 ship never reached this listener
--   (no listener existed at publication time → no event_publication row),
--   so vector_store entries for those skills are stranded.
--
-- Impact pre-fix:
--   - S059 (M55 v2.36.0) JOIN skills + WHERE status='PUBLISHED' filter blocks
--     these vectors from semantic search → user-visible impact = 0
--   - But storage bloat: orphan rows accumulate forever until manual cleanup
--
-- Fix:
--   One-shot DELETE for vector_store rows whose skill is currently SUSPENDED.
--   Idempotent — running on already-clean DB is no-op.
--   Future SUSPENDED events handled by S033 listener; this migration only
--   covers historical data.

DELETE FROM vector_store
 WHERE skill_id IN (SELECT id FROM skills WHERE status = 'SUSPENDED');
