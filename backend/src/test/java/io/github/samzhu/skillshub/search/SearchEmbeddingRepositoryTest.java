package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

@Tag("S186")
class SearchEmbeddingRepositoryTest extends RepositorySliceTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private SearchEmbeddingRepository repo;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
        repo = new SearchEmbeddingRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    @Test
    @DisplayName("AC-S186-5: upsertEmbedding updates only skills embedding columns")
    void upsertEmbeddingUpdatesOnlySkillsEmbeddingColumns() {
        seedSkill("skill-docker", "docker-compose-helper", "PUBLISHED", true,
                "[\"user:sam:read\"]");

        repo.upsertEmbedding("skill-docker", "docker-compose-helper docker-helper Compose deploy helper",
                unitVector(), "test-embedding", Instant.now());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT name, status, is_public, acl_entries::text AS acl_entries,
                       embedding_content, embedding IS NOT NULL AS has_embedding,
                       embedding_model, embedding_updated_at
                  FROM skills
                 WHERE id = ?
                """, "skill-docker");

        assertThat(row.get("name")).isEqualTo("docker-compose-helper");
        assertThat(row.get("status").toString()).isEqualTo("PUBLISHED");
        assertThat(row.get("is_public")).isEqualTo(true);
        assertThat(row.get("acl_entries").toString()).contains("user:sam:read");
        assertThat(row.get("embedding_content"))
                .isEqualTo("docker-compose-helper docker-helper Compose deploy helper");
        assertThat(row.get("has_embedding")).isEqualTo(true);
        assertThat(row.get("embedding_model")).isEqualTo("test-embedding");
        assertThat(row.get("embedding_updated_at")).isInstanceOf(Timestamp.class);
    }

    @Test
    @DisplayName("AC-S186-5: clearEmbedding nulls embedding columns without changing visibility or ACL")
    void clearEmbeddingNullsEmbeddingColumnsWithoutChangingVisibilityOrAcl() {
        seedSkill("skill-docker", "docker-compose-helper", "SUSPENDED", false,
                "[\"user:sam:read\"]");
        repo.upsertEmbedding("skill-docker", "old text", unitVector(), "test-embedding", Instant.now());

        repo.clearEmbedding("skill-docker", Instant.now());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT name, status, is_public, acl_entries::text AS acl_entries,
                       embedding_content IS NULL AS content_null,
                       embedding IS NULL AS embedding_null,
                       embedding_model IS NULL AS model_null
                  FROM skills
                 WHERE id = ?
                """, "skill-docker");

        assertThat(row.get("name")).isEqualTo("docker-compose-helper");
        assertThat(row.get("status").toString()).isEqualTo("SUSPENDED");
        assertThat(row.get("is_public")).isEqualTo(false);
        assertThat(row.get("acl_entries").toString()).contains("user:sam:read");
        assertThat(row.get("content_null")).isEqualTo(true);
        assertThat(row.get("embedding_null")).isEqualTo(true);
        assertThat(row.get("model_null")).isEqualTo(true);
    }

    private void seedSkill(String id, String name, String status, boolean isPublic, String aclEntriesJson) {
        jdbc.update("""
                INSERT INTO skills (
                    id, name, description, author, category, category_display, latest_version,
                    risk_level, status, download_count, created_at, updated_at, acl_entries,
                    is_public, owner_id
                )
                VALUES (?, ?, 'old description', 'sam', 'devops', 'DevOps', '1.0.0',
                    'LOW', ?, 0, NOW(), NOW(), ?::jsonb, ?, 'sam')
                """, id, name, status, aclEntriesJson, isPublic);
    }

    private static float[] unitVector() {
        var vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
