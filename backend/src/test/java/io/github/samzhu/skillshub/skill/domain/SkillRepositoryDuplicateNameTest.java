package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

class SkillRepositoryDuplicateNameTest extends RepositorySliceTestBase {

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S176-7: author/name legacy lookup returns deterministic latest row for duplicate names")
    @Tag("AC-S176-7")
    void authorNameLegacyLookup_returnsDeterministicLatestRowForDuplicateNames() {
        var firstId = UUID.randomUUID().toString();
        var secondId = UUID.randomUUID().toString();
        var author = "s176-author";
        var skillName = "s176-explicit-name";
        var ids = List.of(firstId, secondId);
        try {
            seedSkill(firstId, skillName, author, Instant.parse("2026-05-15T01:00:00Z"));
            seedSkill(secondId, skillName, author, Instant.parse("2026-05-15T02:00:00Z"));

            var found = skillRepo.findByAuthorAndName("S176-AUTHOR", "S176-EXPLICIT-NAME").orElseThrow();

            assertThat(found.getId()).as("legacy author/name route chooses the newest duplicate").isEqualTo(secondId);
            assertThat(found.getName()).isEqualTo(skillName);
        } finally {
            jdbc.update("DELETE FROM skills WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
        }
    }

    private void seedSkill(String id, String name, String author, Instant createdAt) {
        jdbc.update("""
                INSERT INTO skills
                  (id, name, description, author, category, status, download_count,
                   created_at, updated_at, acl_entries, owner_id)
                VALUES (:id, :name, 'S176 repository fixture', :author, 'automation',
                        'PUBLISHED', 0, :createdAt, :createdAt, '[]'::jsonb, :author)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("author", author)
                .addValue("createdAt", java.sql.Timestamp.from(createdAt)));
    }
}
