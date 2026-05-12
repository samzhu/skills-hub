-- ============================================================================
-- S159b Round 2 V21 — Add category_display column to preserve original CamelCase
-- ============================================================================
--
-- 背景：S159b Round 1 把 skills.category 一律 lowercase 化（V20）+ frontend
-- `capitalize()` 還原 display；但 lossy — `"DevOps"` → `"devops"` → `"Devops"`，
-- 失去原 CamelCase。V07 Playwright 抓到 `getByText('DevOps')` FAIL。
--
-- Round 2 設計（spec §7.5c）：dual-column
--   - `category`         canonical / search key / V20 CHECK enforce lowercase（不動）
--   - `category_display` display only / 保留原始 case / V21 新增
--
-- Backfill 策略：對既有 row 跑 `initcap(category)` lossy best-effort。
--   - 'devops'  → 'Devops'（仍非原 'DevOps'，但 dev/LAB row 數低、無真用戶可接受）
--   - 'testing' → 'Testing'
--   - 'ci/cd'   → 'Ci/Cd'（PostgreSQL initcap 對非字母當 word 邊界）
-- 新寫入透過 aggregate 雙寫，從此原始 CamelCase 保留。
--
-- 冪等性：Flyway 不重跑已 applied migration；UPDATE 走
--   WHERE category IS NOT NULL AND category_display IS NULL
-- → 重跑（reset）場景下，已 backfill 的 row 不再被動。
-- ============================================================================

ALTER TABLE skills ADD COLUMN category_display VARCHAR(50);

-- ----------------------------------------------------------------------------
-- Backfill：既有 row 走 initcap 還原 first-letter capitalize（lossy）
-- ----------------------------------------------------------------------------
UPDATE skills
SET category_display = initcap(category)
WHERE category IS NOT NULL
  AND category_display IS NULL;
