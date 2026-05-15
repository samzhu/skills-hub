package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillNameUniquenessMigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S176-6: Flyway schema removes skills_name_key and allows duplicate skill names")
    @Tag("AC-S176-6")
    void flywaySchema_removesNameUniqueConstraintAndAllowsDuplicateSkillNames() {
        var constraints = jdbc.queryForList("""
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'skills'
                  AND c.contype = 'u'
                  AND c.conname = 'skills_name_key'
                """, Map.of());
        assertThat(constraints).as("V25 drops the V1 global skills.name UNIQUE constraint").isEmpty();

        var firstId = UUID.randomUUID().toString();
        var secondId = UUID.randomUUID().toString();
        var skillName = "s176-explicit-name";
        var ids = List.of(firstId, secondId);
        try {
            seedSkill(firstId, skillName, "s176-author-a", Instant.now().minusSeconds(60));
            seedSkill(secondId, skillName, "s176-author-b", Instant.now());

            var count = jdbc.queryForObject("""
                    SELECT count(*)::int
                    FROM skills
                    WHERE id IN (:ids)
                      AND name = :name
                    """, new MapSqlParameterSource()
                    .addValue("ids", ids)
                    .addValue("name", skillName), Integer.class);
            assertThat(count).as("two different skills may share the same platform name").isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM skills WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
        }
    }

    private void seedSkill(String id, String name, String author, Instant createdAt) {
        jdbc.update("""
                INSERT INTO skills
                  (id, name, description, author, category, status, download_count,
                   created_at, updated_at, acl_entries, owner_id)
                VALUES (:id, :name, 'S176 migration fixture', :author, 'automation',
                        'PUBLISHED', 0, :createdAt, :createdAt, '[]'::jsonb, :author)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("author", author)
                .addValue("createdAt", java.sql.Timestamp.from(createdAt)));
    }
}
