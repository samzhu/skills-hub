package io.github.samzhu.skillshub.search;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.pgvector.PGvector;

/**
 * Writes semantic-search embeddings into the infrastructure columns on the {@code skills} row.
 *
 * <p>S186 keeps Search Embedding physically colocated with Skill read state while leaving
 * the {@code Skill} aggregate unmapped to these columns.
 *
 * @see SearchProjection
 * @see SemanticSearchService
 */
class SearchEmbeddingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    SearchEmbeddingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void upsertEmbedding(String skillId, String content, float[] embedding, String model, Instant updatedAt) {
        jdbc.update("""
                UPDATE skills
                   SET embedding_content = :content,
                       embedding = :embedding,
                       embedding_model = :model,
                       embedding_updated_at = :updatedAt
                 WHERE id = :skillId
                """, Map.of(
                "skillId", skillId,
                "content", content,
                "embedding", new SqlParameterValue(SqlTypeValue.TYPE_UNKNOWN, new PGvector(embedding)),
                "model", model,
                "updatedAt", Timestamp.from(updatedAt)));
    }

    void clearEmbedding(String skillId, Instant updatedAt) {
        jdbc.update("""
                UPDATE skills
                   SET embedding_content = NULL,
                       embedding = NULL,
                       embedding_model = NULL,
                       embedding_updated_at = :updatedAt
                 WHERE id = :skillId
                """, Map.of(
                "skillId", skillId,
                "updatedAt", Timestamp.from(updatedAt)));
    }
}
