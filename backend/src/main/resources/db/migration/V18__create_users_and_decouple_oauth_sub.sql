-- ============================================================================
-- S154 V18 — Platform user_id 解耦 OAuth sub
-- ============================================================================
--
-- 範圍（per spec §2.8 — 2026-05-10 user 決策 fresh schema, no backfill）：
--   1. users 表 — 平台 user_id (`u_<6hex>`) 跟 OAuth sub 多供應商解耦
--   2. skills.author_name_snapshot column — publish 時凍結 author 顯示名稱
--
-- 設計依據：
--   - spec §2.2 設計核心：users.id 為 platform user_id，未來 skills.author /
--     skills.owner_id / skills.acl_entries / skill_grants.principal_id 全用此 id；
--     OAuth sub 只在 users 表保留作為登入比對 key
--   - spec §2.3 OAuth provider schema 預留：UNIQUE(oauth_provider, sub) 允許未來
--     加 GitHub / GitLab 等 provider 而 sub 在不同 provider 可重複
--   - spec §2.6 Username slug：handle 自 OAuth email 推導 + collision retry
--     (UPSERT layer 處理；DB 層只保 UNIQUE constraint)
--   - 既存 3 筆 dev row + skill_grants principal sub 全棄（user 確認 docker
--     compose down -v fresh 重跑）— 故無 backfill DO $$ block
--
-- 後續：
--   - T02 建 User entity / repository / UserUpsertService（applicable handle 與
--     user_id 唯一性 retry 由 service 層處理，DB 層只擔保 constraint）
-- ============================================================================

-- 1. users — platform 身份主表
CREATE TABLE users (
    id                     VARCHAR(20)  PRIMARY KEY,        -- "u_<6hex>" — platform user_id
    oauth_provider         VARCHAR(20)  NOT NULL,           -- 'google' (MVP)；未來 'github' etc
    sub                    VARCHAR(255) NOT NULL,           -- OAuth provider 的 sub claim
    email                  VARCHAR(320) NOT NULL,           -- RFC 5321 max email length
    name                   VARCHAR(255),                    -- nullable — OAuth name claim 可缺
    handle                 VARCHAR(64)  UNIQUE NOT NULL,    -- username slug; 用於 GET /skills/{author}/{name}
    avatar_url             TEXT,                            -- nullable — OAuth picture claim
    contact_email_public   BOOLEAN      NOT NULL DEFAULT FALSE,  -- 控制 API 是否回 authorEmail
    created_at             TIMESTAMPTZ  NOT NULL,
    last_seen_at           TIMESTAMPTZ  NOT NULL,
    -- 同 provider 內 sub 唯一；不同 provider 同 sub 可共存（雖然實務罕見）
    UNIQUE (oauth_provider, sub)
);

-- email 索引 — UserResolver / 後台找人用；非 UNIQUE（同 email 跨 provider 理論上可能）
CREATE INDEX idx_users_email ON users (email);

-- 2. skills.author_name_snapshot — publish 時 freeze 顯示名稱
--    nullable：既存 row 無資料；新 publish 由 Skill aggregate 寫入（T04）
ALTER TABLE skills ADD COLUMN author_name_snapshot VARCHAR(255);
