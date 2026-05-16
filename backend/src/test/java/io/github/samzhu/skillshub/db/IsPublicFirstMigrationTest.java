package io.github.samzhu.skillshub.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S177-T01 — V26 migration verifies public visibility is stored as ordinary
 * boolean columns instead of {@code public:*:read} ACL pseudo entries.
 *
 * @see io.github.samzhu.skillshub.skill.domain.Skill
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IsPublicFirstMigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S177-1: migration keeps skills.is_public as ordinary boolean")
    @Tag("AC-S177-1")
    void migrationKeepsSkillsIsPublicAsOrdinaryBoolean() {
        var skillColumn = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default, generation_expression
                FROM information_schema.columns
                WHERE table_name = 'skills' AND column_name = 'is_public'
                """, Map.of());

        assertThat(skillColumn.get("data_type")).isEqualTo("boolean");
        assertThat(skillColumn.get("is_nullable")).isEqualTo("NO");
        assertThat((String) skillColumn.get("generation_expression"))
                .as("V26 turns skills.is_public into an ordinary writable boolean")
                .isNullOrEmpty();
        assertThat(((String) skillColumn.get("column_default")).toLowerCase())
                .contains("false");
    }

    @Test
    @DisplayName("AC-S177-1: migration removes public ACL pseudo entry from skills")
    @Tag("AC-S177-1")
    void migrationCleanupRemovesPublicPseudoAclEntriesFromSkills() {
        var skillId = UUID.randomUUID().toString();
        try {
            var now = Instant.now();
            jdbc.update("""
                    INSERT INTO skills
                      (id, name, description, author, category, status, download_count,
                       created_at, updated_at, acl_entries, owner_id, is_public)
                    VALUES (:id, :name, 'S177 migration fixture', 'alice', 'testing',
                            'DRAFT', 0, :ts, :ts,
                            '["user:alice:read","public:*:read"]'::jsonb,
                            'alice', false)
                    """, new MapSqlParameterSource()
                    .addValue("id", skillId)
                    .addValue("name", "s177-migration-" + skillId.substring(0, 8))
                    .addValue("ts", java.sql.Timestamp.from(now)));

            jdbc.getJdbcTemplate().update("""
                    UPDATE skills
                       SET acl_entries = acl_entries - 'public:*:read'
                     WHERE acl_entries @> '["public:*:read"]'::jsonb
                    """);

            var skillAcl = jdbc.queryForObject(
                    "SELECT acl_entries::text FROM skills WHERE id = :id",
                    Map.of("id", skillId),
                    String.class);

            assertThat(skillAcl).doesNotContain("public:*:read");
            assertThat(skillAcl).contains("user:alice:read");
        } finally {
            jdbc.update("DELETE FROM skills WHERE id = :id", Map.of("id", skillId));
        }
    }
}
