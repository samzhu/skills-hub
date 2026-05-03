-- S098e2-T02: skills 加 review-rating projection columns
--
-- average_rating + review_count 由 review module 的
-- SkillRatingProjectionListener AFTER_COMMIT 訂閱 ReviewCreatedEvent /
-- ReviewDeletedEvent 後呼叫 SkillRatingService.refresh(skillId) 更新。
--
-- DEFAULT：尚無 review 的 skill 仍可被 SELECT 不丟 NULL；前端 Skill DTO
-- 直接消費 0.00 / 0 而不需 nullable 處理。
--
-- NUMERIC(3,2)：精度 0.00 ~ 5.00（rating ∈ [1,5]，AVG 最多 5.00）。

ALTER TABLE skills
    ADD COLUMN average_rating NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN review_count   BIGINT       NOT NULL DEFAULT 0;
