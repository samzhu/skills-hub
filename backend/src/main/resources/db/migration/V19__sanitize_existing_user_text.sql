-- ============================================================================
-- S161c V19 — Backfill: strip HTML markup from existing user-submitted text
-- ============================================================================
--
-- 範圍：對 S161 / S161b 套用 PlainTextDeserializer **之前**已落地的舊資料做一次性
-- HTML strip，把 stored XSS payload 清掉。新進資料由 Jackson 端 deserializer 自動處理，
-- 不會再有 raw <script> / <img onerror=...> 進 DB。
--
-- 涵蓋欄位（與 PlainTextDeserializer 套用點 1:1 對齊，per S161 §6.5/§6.6）：
--   - reviews.content              (S161 Phase 1)
--   - flags.description            (S161b)
--   - collections.name             (S161b)
--   - collections.description      (S161b)
--
-- 不涵蓋：
--   - requests.title / requests.description — 屬 S161b' / OWASP markdown allowlist 範疇
--     （markdown 結構不能直接 regex strip 否則破合法 <inline>code</inline>）
--   - skills.description / skills.name      — owner-published 受 SKILL.md validator 約束，
--     不在本 sanitize 範圍
--
-- 策略：mirror PlainTextDeserializer 雙 pass：
--   pass 1: <script>...</script> / <style>...</style> 整段（含內容）strip
--   pass 2: 任何 <tagname...> / </tagname> 標籤 strip（保留 a < b 等非 tag 用法）
--
-- 邊界：reviews.content 有 CHECK (length(content) BETWEEN 1 AND 2000)。若該 row 內容
-- 全是 HTML，strip 後變空字串會違反 CHECK；用 NULLIF + COALESCE 退化到 placeholder
-- 文字，保留 audit trail（讓 row 不消失，DBA / 客服可以看到「曾有 HTML payload」）。
--
-- 冪等性：WHERE content ~ '<[a-zA-Z/]' 只 match 含 HTML-tag-shape 的 row；strip 後
-- 該 pattern 不再 match → 重跑 0 row affected。Flyway 預設不重跑已 applied migration，
-- 但本 SQL 也支援人工 reset + 重跑場景。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- reviews.content
-- ----------------------------------------------------------------------------
UPDATE reviews
SET content = COALESCE(
    NULLIF(
        regexp_replace(
            regexp_replace(content,
                '<(script|style)\b[^>]*>.*?</\1\s*>',  -- pass 1: tag + 內容
                '',
                'gi'),
            '<[a-zA-Z/][^>]*>',                       -- pass 2: 任何 HTML tag
            '',
            'g'),
        ''
    ),
    '[redacted by S161c: original review content was HTML-only]'
)
WHERE content ~ '<[a-zA-Z/]';

-- ----------------------------------------------------------------------------
-- flags.description（nullable，可直接被 strip 成空字串或 null）
-- ----------------------------------------------------------------------------
UPDATE flags
SET description = NULLIF(
    regexp_replace(
        regexp_replace(description,
            '<(script|style)\b[^>]*>.*?</\1\s*>',
            '',
            'gi'),
        '<[a-zA-Z/][^>]*>',
        '',
        'g'),
    ''
)
WHERE description ~ '<[a-zA-Z/]';

-- ----------------------------------------------------------------------------
-- collections.name（NOT NULL VARCHAR(200)；name 全空會破 UX，用 placeholder 保留）
-- ----------------------------------------------------------------------------
UPDATE collections
SET name = COALESCE(
    NULLIF(
        regexp_replace(
            regexp_replace(name,
                '<(script|style)\b[^>]*>.*?</\1\s*>',
                '',
                'gi'),
            '<[a-zA-Z/][^>]*>',
            '',
            'g'),
        ''
    ),
    '[redacted collection name]'
)
WHERE name ~ '<[a-zA-Z/]';

-- ----------------------------------------------------------------------------
-- collections.description（nullable）
-- ----------------------------------------------------------------------------
UPDATE collections
SET description = NULLIF(
    regexp_replace(
        regexp_replace(description,
            '<(script|style)\b[^>]*>.*?</\1\s*>',
            '',
            'gi'),
        '<[a-zA-Z/][^>]*>',
        '',
        'g'),
    ''
)
WHERE description ~ '<[a-zA-Z/]';
