-- S186-T01: move semantic embedding storage onto the canonical skills row.
-- Skill aggregate intentionally does not map these columns; normal save() keeps them untouched.
ALTER TABLE skills
    ADD COLUMN IF NOT EXISTS embedding_content TEXT,
    ADD COLUMN IF NOT EXISTS embedding VECTOR(768),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64),
    ADD COLUMN IF NOT EXISTS embedding_updated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_skills_embedding_hnsw
    ON skills USING HNSW (embedding vector_cosine_ops);

DROP TABLE IF EXISTS vector_store;
