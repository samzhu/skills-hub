-- ============================================================================
-- S159b V20 — Normalize skills.category to lowercase + add CHECK constraint
-- ============================================================================
--
-- 範圍：把 `skills.category` 從 mix-case（"Testing" / "testing" / " TESTING "）
-- 統一為 lowercase + trimmed。加 CHECK constraint 防未來 drift。
--
-- 為什麼：
--   - query `?category=Testing` 找不到 `category="testing"` row（case-sensitive 比對）
--   - V1 schema 無 CHECK；aggregate write path 也未 lowercase → 任何 caller 帶大寫
--     即落 DB，distinct(category) 出現 case-only 重複，UI 顯「Testing」+「testing」
--     兩個 chip。
--
-- 配套（同 S159b T01 落地）：
--   - `Skill.create()` / `Skill.update()` 加 `.toLowerCase()` 寫入端 normalize
--   - `SkillQueryController` `?category=` 起手 lowercase（caller 大小寫無感）
--   - 前端 `capitalize()` helper 在 6 個 display 站點顯首字母大寫
--
-- 順序：先 backfill 既存 row（lowercase 後 CHECK 不會被自己 violate），再 ADD CHECK。
--
-- 冪等性：Flyway 不重跑已 applied migration；UPDATE 走 `WHERE category IS NOT NULL`
-- 只動有值的 row。重跑（reset 場景）也 idempotent — 已 lowercase 的 row 再 lower(trim(...))
-- 仍回相同值。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Backfill：把既存 mix-case row 拉平為 lowercase + trimmed
-- ----------------------------------------------------------------------------
UPDATE skills
SET category = lower(trim(category))
WHERE category IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 2. CHECK constraint：未來任何 INSERT/UPDATE 帶大寫 / 含 leading-trailing space
--    的 category 直接拒收（DataIntegrityViolationException）
--
--    `NULL` 允許 — V1 column 設計即 `VARCHAR(50)` 無 NOT NULL（既有 row 也有 NULL）
-- ----------------------------------------------------------------------------
ALTER TABLE skills
  ADD CONSTRAINT skills_category_lowercase
  CHECK (category IS NULL OR category = lower(category));
