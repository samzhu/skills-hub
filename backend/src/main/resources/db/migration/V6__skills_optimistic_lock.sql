-- ============================================================================
-- S024 V6 — Skill aggregate 加 @Version 樂觀鎖欄位（per ADR-002 Phase 2）
-- ============================================================================
--
-- 範圍：
--   為 skills 表加 version BIGINT NOT NULL DEFAULT 0 欄位，提供 Spring Data JDBC
--   @Version 樂觀鎖支援。S024 Skill aggregate 改寫為 @Table 充血聚合後，並發更新
--   透過此欄位偵測（衝突 → OptimisticLockingFailureException）。
--
-- 設計依據：
--   - ADR-002 §1.1 + §5：Phase 2 Skill aggregate state-based migration
--   - spec/2026-04-29-S024-skill-state-based-aggregate.md §2.10 + §4.6
--   - deepwiki/spring-data-jdbc-modulith/aggregate-design.md §1（@Version 行為）
--
-- 既有 row 預設 version=0；新 INSERT 由 prepareVersionForInsert 設 0；
-- 後續 UPDATE 由 prepareVersionForUpdate 自動 +1。
-- ============================================================================

ALTER TABLE skills
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN skills.version IS
    'Spring Data JDBC @Version optimistic lock; per ADR-002 Phase 2 (S024)';
