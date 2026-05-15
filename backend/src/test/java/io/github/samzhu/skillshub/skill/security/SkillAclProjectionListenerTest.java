package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillRevokedEvent;

/**
 * S114a T04 — SkillAclProjectionListener Scenario 整合測試。
 *
 * <p>AC-1: SkillCreatedEvent → OWNER grant seeded + acl_entries rebuilt with 3 alice entries.
 * AC-2: SkillGrantedEvent → acl_entries includes user:bob:read.
 * AC-3: public VIEWER grant → is_public=TRUE.
 * AC-4: SkillRevokedEvent → acl_entries no longer contains user:bob:read.
 * AC-10: same event twice → idempotent (no duplicate entries).
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration.class)
class SkillAclProjectionListenerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillGrantRepository grantRepo;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM skill_grants");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-S177-1b: SkillCreatedEvent rebuilds ACL from create-time OWNER grant")
    void onSkillCreated_seedsOwnerGrantAndRebuildsAcl(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");
        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));

        scenario.publish(new SkillCreatedEvent(skillId, "test-skill", "desc", "alice", "Test"))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> loadAclEntries(skillId))
                .andVerify(entries -> {
                    assertThat(entries).contains("user:alice:read");
                    assertThat(entries).contains("user:alice:write");
                    assertThat(entries).contains("user:alice:delete");
                });
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-S177-5b: rebuild writes full skill ACL but read-only vector ACL")
    void onGranted_userEditor_rebuildsSkillsAndVectorAcl(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");
        insertVectorRow(skillId, "alice");

        // seed OWNER grant for alice (normally done by AC-1 listener, here direct for isolation)
        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));
        var bobGrant = SkillGrant.create(skillId, "user", "bob", Role.EDITOR, "alice");
        grantRepo.save(bobGrant);

        scenario.publish(new SkillGrantedEvent(skillId, bobGrant.getId()))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> loadAclEntries(skillId))
                .andVerify(entries -> {
                    assertThat(entries).contains("user:bob:read", "user:bob:write");
                    assertThat(entries).doesNotContain("user:bob:delete");
                    assertThat(loadVectorAclEntries(skillId))
                            .containsExactlyInAnyOrder("user:alice:read", "user:bob:read")
                            .doesNotContain("user:alice:write", "user:alice:delete", "user:bob:write");
                });
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: group VIEWER grant → skills/vector_store acl_entries 同步")
    void onGranted_groupViewer_rebuildsSkillsAndVectorAcl(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");
        insertVectorRow(skillId, "alice");

        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));
        var groupGrant = SkillGrant.create(skillId, "group", "g_d4e5f6", Role.VIEWER, "alice");
        grantRepo.save(groupGrant);

        scenario.publish(new SkillGrantedEvent(skillId, groupGrant.getId()))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> loadAclEntries(skillId))
                .andVerify(entries -> {
                    assertThat(entries).contains("group:g_d4e5f6:read");
                    assertThat(loadVectorAclEntries(skillId))
                            .containsExactlyInAnyOrder("user:alice:read", "group:g_d4e5f6:read")
                            .doesNotContain("user:alice:write", "user:alice:delete");
                });
    }

    @Test
    @Tag("AC-S177-5b")
    @DisplayName("AC-S177-5b: rebuild never writes public pseudo ACL into vector_store")
    void onGranted_publicGrant_setsVectorPublicWithoutPublicPseudoAcl(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");
        insertVectorRow(skillId, "alice");
        jdbc.update("UPDATE skills SET is_public = TRUE WHERE id = ?", skillId);

        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));
        var publicGrant = SkillGrant.createWithId("pub" + UUID.randomUUID().toString().replace("-", "").substring(0, 9),
                skillId, "public", "*", Role.VIEWER, "alice");
        grantRepo.save(publicGrant);

        scenario.publish(new SkillGrantedEvent(skillId, publicGrant.getId()))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> loadVectorIsPublic(skillId))
                .andVerify(isPublic -> {
                    assertThat(isPublic).isTrue();
                    assertThat(loadVectorAclEntries(skillId))
                            .containsExactly("user:alice:read")
                            .doesNotContain("public:*:read");
                });
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: SkillRevokedEvent → acl_entries no longer contains user:bob:read")
    void onRevoked_rebuildsAclWithoutRevokedEntry(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");

        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));
        var bobGrant = SkillGrant.create(skillId, "user", "bob", Role.VIEWER, "alice");
        grantRepo.save(bobGrant);

        // first seed the ACL with bob's entry (simulate prior grant)
        jdbc.update("UPDATE skills SET acl_entries = '[\"user:alice:read\",\"user:bob:read\"]'::jsonb WHERE id = ?", skillId);

        // now revoke bob's grant from DB, then fire revoke event
        grantRepo.deleteById(bobGrant.getId());

        scenario.publish(new SkillRevokedEvent(skillId, bobGrant.getId()))
                .andWaitAtMost(java.time.Duration.ofSeconds(5))
                .andWaitForStateChange(() -> {
                    var entries = loadAclEntries(skillId);
                    // state change = bob's read entry removed
                    if (entries != null && !entries.contains("user:bob:read")) return entries;
                    return null;
                })
                .andVerify(entries -> assertThat(entries).doesNotContain("user:bob:read"));
    }

    @Test
    @Tag("AC-10")
    @DisplayName("AC-10: same SkillGrantedEvent fired twice → idempotent, no duplicate acl entries")
    void onGranted_duplicateEvent_idempotent(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        insertSkill(skillId, "alice");

        grantRepo.save(SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice"));
        var bobGrant = SkillGrant.create(skillId, "user", "bob", Role.VIEWER, "alice");
        grantRepo.save(bobGrant);

        var event = new SkillGrantedEvent(skillId, bobGrant.getId());

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                })
                .andWaitForStateChange(() -> loadAclEntries(skillId))
                .andVerify(entries -> {
                    assertThat(entries).contains("user:bob:read");
                    // no duplicates
                    long bobReadCount = entries.stream().filter("user:bob:read"::equals).count();
                    assertThat(bobReadCount).isEqualTo(1);
                });
    }

    private void insertSkill(String id, String author) {
        var now = java.sql.Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status,
                    download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, 'test', ?, 'test', 'PUBLISHED', 0, ?, ?, ?)""",
                id, "test-skill-" + id.substring(0, 8), author, now, now, author);
    }

    private void insertVectorRow(String skillId, String owner) {
        jdbc.update("""
                INSERT INTO vector_store (id, content, metadata, owner, skill_id, acl_entries)
                VALUES (?::uuid, 'vector-content', '{}'::json, ?, ?, '[]'::jsonb)
                """, UUID.randomUUID().toString(), owner, skillId);
    }

    @SuppressWarnings("unchecked")
    private List<String> loadAclEntries(String skillId) {
        return jdbc.query(
                "SELECT jsonb_array_elements_text(acl_entries) FROM skills WHERE id = ? AND acl_entries IS NOT NULL",
                (rs, n) -> rs.getString(1),
                skillId);
    }

    private List<String> loadVectorAclEntries(String skillId) {
        return jdbc.query(
                "SELECT jsonb_array_elements_text(acl_entries) FROM vector_store WHERE skill_id = ? AND acl_entries IS NOT NULL",
                (rs, n) -> rs.getString(1),
                skillId);
    }

    private Boolean loadVectorIsPublic(String skillId) {
        return jdbc.queryForObject(
                "SELECT is_public FROM vector_store WHERE skill_id = ?",
                Boolean.class,
                skillId);
    }
}
