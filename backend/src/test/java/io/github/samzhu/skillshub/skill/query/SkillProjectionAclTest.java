package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent;

/**
 * S016 T5 — SkillProjection 對 SkillAclGranted/Revoked event 的 read-side 反映。
 *
 * <p>對應 spec §4.14：listener 透過 SkillReadModelRepository.appendAclEntry / removeAclEntry
 * atomic UPDATE 維護 {@code skills.acl_entries}；冪等與 fail-secure 兩個面向都要驗。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillProjectionAclTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private SkillReadModelRepository skillRepo;

    @Test
    @DisplayName("AC-9 read-side: SkillAclGrantedEvent 觸發 → acl_entries append")
    @Tag("AC-9")
    void grantedEvent_appendsEntryToReadModel() {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));

        publisher.publishEvent(new SkillAclGrantedEvent(
                skillId, "group", "engineering", "read", "alice"));

        var entries = skillRepo.findById(skillId).orElseThrow().aclEntries();
        assertThat(entries).contains("group:engineering:read");
        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("AC-9 read-side: 同 entry 二次 grant → 不重複加（NOT @> 條件）")
    @Tag("AC-9")
    void grantedEvent_duplicateEntry_isIdempotent() {
        var skillId = seedSkill(List.of("user:alice:read", "group:engineering:read"));

        publisher.publishEvent(new SkillAclGrantedEvent(
                skillId, "group", "engineering", "read", "alice"));

        var entries = skillRepo.findById(skillId).orElseThrow().aclEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries).containsExactlyInAnyOrder(
                "user:alice:read", "group:engineering:read");
    }

    @Test
    @DisplayName("AC-10 read-side: SkillAclRevokedEvent 觸發 → acl_entries 移除對應 entry")
    @Tag("AC-10")
    void revokedEvent_removesEntryFromReadModel() {
        var skillId = seedSkill(List.of(
                "user:alice:read", "user:alice:write", "group:engineering:read"));

        publisher.publishEvent(new SkillAclRevokedEvent(
                skillId, "group", "engineering", "read", "alice"));

        var entries = skillRepo.findById(skillId).orElseThrow().aclEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries).doesNotContain("group:engineering:read");
        assertThat(entries).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write");
    }

    @Test
    @DisplayName("AC-10 read-side: revoke 不存在 entry 時 read model 不變（safety net；aggregate 已驗 invariant）")
    @Tag("AC-10")
    void revokedEvent_missingEntry_keepsReadModelUnchanged() {
        var skillId = seedSkill(List.of("user:alice:read"));

        publisher.publishEvent(new SkillAclRevokedEvent(
                skillId, "user", "ghost", "read", "alice"));

        var entries = skillRepo.findById(skillId).orElseThrow().aclEntries();
        assertThat(entries).containsExactly("user:alice:read");
    }

    private String seedSkill(List<String> aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(new SkillReadModel(
                id,
                "proj-acl-" + id.substring(0, 8),
                "Projection ACL test fixture",
                "alice",
                "Testing",
                "1.0.0",
                "LOW",
                "PUBLISHED",
                0L,
                now, now,
                aclEntries));
        return id;
    }
}
