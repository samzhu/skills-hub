-- V13: S098a3-2 — 加 skill_versions.file_count 給 PublishValidatePage upload-strip 顯實值。
-- ----------------------------------------------------------------------------
-- 既有 row file_count = 0 = legacy unknown signal；frontend hide 該欄
-- (per spec §2.5 trim — MVP 不 backfill；下次 publish 才會 update)。
--
-- INTEGER 大小：MVP zip 內 ≤ 1000 個 entries（極端情況），int32 足夠。
-- ----------------------------------------------------------------------------

ALTER TABLE skill_versions
    ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN skill_versions.file_count IS
    'S098a3-2: zip entry count (excluding directories); 0 = legacy row before this column added';
