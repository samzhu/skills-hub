package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;

/**
 * S186-T04 — ACL projection only rebuilds {@code skills.acl_entries}.
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration.class)
@Tag("S186")
class SkillAclProjectionListenerEmbeddingColocationTest {
    private static final String REMOVED_VECTOR_TABLE = "vector" + "_store";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillGrantRepository grantRepo;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC-S186-4: grant projection updates skills ACL without touching legacy vector table")
    void grantProjectionUpdatesSkillsAclWithoutTouchingVectorStore(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        seedSkill(skillId, "u_alice1");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public." + REMOVED_VECTOR_TABLE + "')", String.class))
                .isNull();

        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice1", Role.OWNER, "u_alice1"));
        var bobGrant = SkillGrant.create(skillId, "user", "u_bob111", Role.VIEWER, "u_alice1");
        grantRepo.save(bobGrant);

        scenario.publish(new SkillGrantedEvent(skillId, bobGrant.getId()))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> {
                    var entries = loadAclEntries(skillId);
                    return entries.contains("user:u_bob111:read") ? entries : null;
                })
                .andVerify(entries -> {
                    assertThat(entries).contains("user:u_bob111:read");
                    assertThat(entries).doesNotContain("public:*:read");
                });
    }

    private void seedSkill(String id, String owner) {
        var now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, risk_level, status,
                    download_count, created_at, updated_at, owner_id, is_public, acl_entries)
                VALUES (?, ?, 'test', ?, 'testing', 'LOW', 'PUBLISHED', 0, ?, ?, ?, FALSE, '[]'::jsonb)
                """, id, "test-skill-" + id.substring(0, 8), owner, now, now, owner);
    }

    private List<String> loadAclEntries(String skillId) {
        return jdbc.query(
                "SELECT jsonb_array_elements_text(acl_entries) FROM skills WHERE id = ? AND acl_entries IS NOT NULL",
                (rs, n) -> rs.getString(1),
                skillId);
    }
}
