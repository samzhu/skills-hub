package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S144-T01 — verifies the service-level hard delete contract before HTTP/audit/storage wiring.
 *
 * <p>The test seeds both FK-cascade rows and soft-FK rows, then calls
 * {@link SkillCommandService#deleteSkill(String, String)} through the same repository transaction path
 * production will use. Audit insertion is intentionally out of scope for T01; it is covered by T02.
 */
@Import({SkillCommandService.class, PackageService.class, SkillValidator.class})
class SkillCommandServiceDeleteTest extends RepositorySliceTestBase {

    @Autowired SkillCommandService commandService;
    @Autowired SkillRepository skillRepo;
    @Autowired SkillVersionRepository versionRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM collection_skills");
        jdbc.update("DELETE FROM collections");
        jdbc.update("DELETE FROM skill_subscriptions");
        jdbc.update("DELETE FROM skill_scores");
        jdbc.update("DELETE FROM vector_store");
        jdbc.update("DELETE FROM skill_grants");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM flags");
        jdbc.update("DELETE FROM download_events");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-S144-1")
    @DisplayName("AC-S144-1: deleteSkill hard-deletes skill, cascades FK rows, clears soft-FK rows, preserves domain_events")
    void deleteSkill_removesSkillAndDependentRows_butPreservesDomainEvents() {
        var skillId = seedPublishedSkillWithTwoVersions();
        var versionId = versionRepo.findBySkillIdAndVersion(skillId, "1.0.0").orElseThrow().getId();
        seedFkCascadeRows(skillId);
        seedSoftFkRows(skillId, versionId);
        seedExistingAuditRow(skillId);

        commandService.deleteSkill(skillId, "alice");

        assertThat(skillRepo.findById(skillId)).isEmpty();
        assertThat(count("skills", skillId)).isZero();
        assertThat(count("skill_versions", skillId)).isZero();
        assertThat(count("download_events", skillId)).isZero();
        assertThat(count("vector_store", skillId)).isZero();
        assertThat(count("reviews", skillId)).isZero();
        assertThat(count("flags", skillId)).isZero();
        assertThat(count("skill_grants", skillId)).isZero();

        assertThat(count("skill_subscriptions", skillId)).isZero();
        assertThat(count("skill_scores", skillId)).isZero();
        assertThat(count("collection_skills", skillId)).isZero();
        assertThat(count("notifications", skillId)).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM domain_events WHERE aggregate_id = ?",
                Integer.class, skillId)).isEqualTo(1);
    }

    @Test
    @Tag("AC-S144-3")
    @DisplayName("AC-S144-3: deleteSkill on missing id throws NoSuchElementException")
    void deleteSkill_missingId_throwsNotFound() {
        assertThatThrownBy(() -> commandService.deleteSkill("missing-skill-id", "alice"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing-skill-id");
    }

    @Test
    @Tag("AC-S144-6")
    @DisplayName("AC-S144-6: deleted skill disappears from public browse list and direct lookup")
    void deleteSkill_removesSkillFromBrowseListAndDirectLookup() {
        var skillId = seedPublishedSkillWithTwoVersions();

        assertThat(publicBrowseSkillIds()).contains(skillId);

        commandService.deleteSkill(skillId, "alice");

        assertThat(publicBrowseSkillIds()).doesNotContain(skillId);
        assertThat(skillRepo.findById(skillId)).isEmpty();
    }

    private String seedPublishedSkillWithTwoVersions() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("delete-service", "Delete service fixture", "alice", "DevOps"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "skills/%s/1.0.0/skill.zip".formatted(skillId), 100, 1, Map.of()));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.1.0", "skills/%s/1.1.0/skill.zip".formatted(skillId), 110, 1, Map.of()));
        return skillId;
    }

    private void seedFkCascadeRows(String skillId) {
        jdbc.update("""
                INSERT INTO download_events (id, skill_id, version, downloaded_at)
                VALUES (?, ?, '1.0.0', now())
                """, UUID.randomUUID().toString(), skillId);
        jdbc.update("""
                INSERT INTO flags (id, skill_id, type, description, reported_by, created_at, status)
                VALUES (?, ?, 'SECURITY', 'bad script', 'bob', now(), 'OPEN')
                """, UUID.randomUUID().toString(), skillId);
        jdbc.update("""
                INSERT INTO reviews (id, skill_id, author_id, rating, content, created_at, updated_at)
                VALUES (?, ?, 'bob', 5, 'useful', now(), now())
                """, UUID.randomUUID().toString(), skillId);
        jdbc.update("""
                INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by)
                VALUES (?, ?, 'user', 'bob', 'VIEWER', 'alice')
                """, UUID.randomUUID().toString(), skillId);
        jdbc.update("""
                INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id, acl_entries)
                SELECT gen_random_uuid(), 'delete-service', '{}'::json,
                       ('[' || array_to_string(array_fill(0.0::double precision, ARRAY[768]), ',') || ']')::vector,
                       'alice', ?, '["user:alice:read"]'::jsonb
                """, skillId);
    }

    private void seedSoftFkRows(String skillId, String versionId) {
        jdbc.update("""
                INSERT INTO skill_subscriptions (id, skill_id, subscriber_id)
                VALUES (?, ?, 'bob')
                """, UUID.randomUUID().toString(), skillId);
        jdbc.update("""
                INSERT INTO skill_scores
                    (id, skill_id, skill_version_id, skill_version, axis, total_score, dimensions,
                     evaluated_at, evaluator_version, source_event_id)
                VALUES (?, ?, ?, '1.0.0', 'VALIDATION', 80, '{}'::jsonb, now(), 'test', ?)
                """, UUID.randomUUID().toString(), skillId, versionId, UUID.randomUUID().toString());
        var collectionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO collections (id, name, description, owner_id, category)
                VALUES (?, 'delete collection', 'desc', 'alice', 'DevOps')
                """, collectionId);
        jdbc.update("""
                INSERT INTO collection_skills (collection_id, skill_id, position)
                VALUES (?, ?, 0)
                """, collectionId, skillId);
        jdbc.update("""
                INSERT INTO notifications (id, recipient_id, category, title, skill_id, ref_event_id)
                VALUES (?, 'bob', 'versions', 'new version', ?, ?)
                """, UUID.randomUUID().toString(), skillId, UUID.randomUUID().toString());
    }

    private void seedExistingAuditRow(String skillId) {
        jdbc.update("""
                INSERT INTO domain_events
                    (id, aggregate_id, aggregate_type, event_type, payload, sequence, occurred_at, metadata)
                VALUES (?, ?, 'Skill', 'SkillCreated', '{}'::jsonb, 1, now(), '{}'::jsonb)
                """, UUID.randomUUID().toString(), skillId);
    }

    private int count(String table, String skillId) {
        var column = "collection_skills".equals(table) ? "skill_id" : "skill_id";
        if ("skills".equals(table)) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM skills WHERE id = ?", Integer.class, skillId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, skillId);
    }

    private java.util.List<String> publicBrowseSkillIds() {
        return jdbc.queryForList("""
                SELECT id
                FROM skills
                WHERE status = 'PUBLISHED'
                ORDER BY created_at DESC
                LIMIT 20
                """, String.class);
    }
}
