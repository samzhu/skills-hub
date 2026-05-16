package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

@Import(SearchProjection.class)
@Tag("S186")
class SearchProjectionEmbeddingWriteTest extends RepositorySliceTestBase {

    @Autowired
    private SearchProjection projection;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC-S186-5: SkillVersionPublishedEvent embeds latest SKILL.md frontmatter into skills row")
    void skillVersionPublishedEventEmbedsLatestSkillMdFrontmatterIntoSkillsRow() {
        seedSkill("skill-docker", "docker-compose-helper", "PUBLISHED", true,
                "[\"user:sam:read\"]", "old text");
        var before = Instant.now().minusMillis(1);

        projection.onVersionPublished(SkillVersionPublishedEvent.of(
                "skill-docker", "2.0.0", "skills/skill-docker/2.0.0.zip", 1024L,
                Map.of("name", "docker-helper", "description", "Compose deploy helper"),
                List.of()));

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
        assertThat(row.get("embedding_model").toString()).isNotBlank();
        assertThat(((Timestamp) row.get("embedding_updated_at")).toInstant()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("AC-S186-5: SkillSuspendedEvent clears skills embedding columns")
    void skillSuspendedEventClearsSkillsEmbeddingColumns() {
        seedSkill("skill-docker", "docker-compose-helper", "PUBLISHED", true,
                "[\"user:sam:read\"]", "old text");
        projection.onVersionPublished(SkillVersionPublishedEvent.of(
                "skill-docker", "2.0.0", "skills/skill-docker/2.0.0.zip", 1024L,
                Map.of("name", "docker-helper", "description", "Compose deploy helper"),
                List.of()));

        projection.onSkillSuspended(new SkillSuspendedEvent("skill-docker", "policy", "admin"));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, is_public, acl_entries::text AS acl_entries,
                       embedding_content IS NULL AS content_null,
                       embedding IS NULL AS embedding_null,
                       embedding_model IS NULL AS model_null
                  FROM skills
                 WHERE id = ?
                """, "skill-docker");

        assertThat(row.get("status").toString()).isEqualTo("PUBLISHED");
        assertThat(row.get("is_public")).isEqualTo(true);
        assertThat(row.get("acl_entries").toString()).contains("user:sam:read");
        assertThat(row.get("content_null")).isEqualTo(true);
        assertThat(row.get("embedding_null")).isEqualTo(true);
        assertThat(row.get("model_null")).isEqualTo(true);
    }

    private void seedSkill(String id, String name, String status, boolean isPublic,
            String aclEntriesJson, String embeddingContent) {
        jdbc.update("""
                INSERT INTO skills (
                    id, name, description, author, category, category_display, latest_version,
                    risk_level, status, download_count, created_at, updated_at, acl_entries,
                    is_public, owner_id, embedding_content, embedding_model, embedding_updated_at
                )
                VALUES (?, ?, 'old description', 'sam', 'devops', 'DevOps', '1.0.0',
                    'LOW', ?, 0, NOW(), NOW(), ?::jsonb, ?, 'sam', ?, 'old-model', NOW())
                """, id, name, status, aclEntriesJson, isPublic, embeddingContent);
    }
}
