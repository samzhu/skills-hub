-- V15: S135a skill_scores schema — per-axis quality evaluation results (VALIDATION / IMPLEMENTATION / ACTIVATION)
-- Direction A wide-JSONB: 1 row per axis per evaluation run; dimensions stored as JSONB map.
-- Soft-FK: no FK constraint to skills/skill_versions — preserves history if skill is deleted.
-- Idempotency: deterministic UUID PK + ON CONFLICT (id) DO NOTHING (per AuditEventListener pattern).

CREATE TABLE skill_scores (
    id                VARCHAR(36)   PRIMARY KEY,
    skill_id          VARCHAR(36)   NOT NULL,
    skill_version_id  VARCHAR(36)   NOT NULL,
    skill_version     VARCHAR(50)   NOT NULL,
    axis              VARCHAR(20)   NOT NULL,
    total_score       NUMERIC(5,2)  NOT NULL,
    dimensions        JSONB         NOT NULL DEFAULT '{}',
    evaluated_at      TIMESTAMPTZ   NOT NULL,
    evaluator_version VARCHAR(255)  NOT NULL,
    source_event_id   VARCHAR(36)   NOT NULL,
    CONSTRAINT chk_axis  CHECK (axis IN ('VALIDATION', 'IMPLEMENTATION', 'ACTIVATION')),
    CONSTRAINT chk_total CHECK (total_score >= 0 AND total_score <= 100)
);

-- Supports latest-per-axis lookup and time-range queries per skill.
CREATE INDEX idx_skill_scores_skill_axis_eval ON skill_scores (skill_id, axis, evaluated_at DESC);

-- Supports version-scoped score fetch (QualityScoreController ?versionId= param).
CREATE INDEX idx_skill_scores_version_id ON skill_scores (skill_version_id);
