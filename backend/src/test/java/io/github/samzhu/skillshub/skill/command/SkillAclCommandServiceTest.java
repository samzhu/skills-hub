package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * S016 T4 — SkillCommandService.grantAcl / revokeAcl 整合測試。
 *
 * <p>對應 spec §4.11：grant/revoke 走 saveAndPublish 路徑，event store 新增
 * SkillAclGranted / SkillAclRevoked record；ApplicationEventPublisher publish 對應 application event。
 *
 * <p>使用 Testcontainer + 完整 Spring Context 確保 (a) 真實 PostgreSQL 寫入；
 * (b) JSONB 序列化路徑可運作；(c) sequence UNIQUE 約束被正確套用。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillAclCommandServiceTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private DomainEventRepository eventStore;

    @Test
    @DisplayName("AC-9: grantAcl → eventStore 含 SkillAclGranted + sequence=N+1")
    @Tag("AC-9")
    void grantAcl_persistsEventAndAdvancesSequence() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-grant-svc-" + uniqueSuffix(),
                        "grant via service", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "group", "engineering", "read", "owner"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).hasSize(2);

        var grantedEvent = events.get(1);
        assertThat(grantedEvent.eventType()).isEqualTo("SkillAclGranted");
        assertThat(grantedEvent.aggregateType()).isEqualTo("Skill");
        assertThat(grantedEvent.sequence()).isEqualTo(2L);
        assertThat(grantedEvent.payload()).containsEntry("type", "group");
        assertThat(grantedEvent.payload()).containsEntry("principal", "engineering");
        assertThat(grantedEvent.payload()).containsEntry("permission", "read");
        assertThat(grantedEvent.payload()).containsEntry("grantedBy", "owner");
    }

    @Test
    @DisplayName("AC-10: revokeAcl 移除既存 entry → eventStore 含 SkillAclRevoked")
    @Tag("AC-10")
    void revokeAcl_persistsEvent() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-revoke-svc-" + uniqueSuffix(),
                        "revoke via service", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "group", "engineering", "read", "owner"));
        commandService.revokeAcl(new RevokeAclCommand(
                skillId, "group", "engineering", "read", "owner"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).hasSize(3);

        var revokedEvent = events.get(2);
        assertThat(revokedEvent.eventType()).isEqualTo("SkillAclRevoked");
        assertThat(revokedEvent.sequence()).isEqualTo(3L);
        assertThat(revokedEvent.payload()).containsEntry("revokedBy", "owner");
    }

    @Test
    @DisplayName("AC-9: 重複 grant 同 entry → IllegalStateException + event store 不變")
    @Tag("AC-9")
    void grantAcl_duplicateEntry_throwsAndDoesNotPersist() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-dup-svc-" + uniqueSuffix(),
                        "duplicate grant", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "user", "alice", "read", "owner"));

        var beforeCount = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();

        assertThatThrownBy(() -> commandService.grantAcl(new GrantAclCommand(
                skillId, "user", "alice", "read", "owner")))
                .isInstanceOf(IllegalStateException.class);

        var afterCount = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    @Test
    @DisplayName("AC-10: revoke 不存在 entry → IllegalStateException")
    @Tag("AC-10")
    void revokeAcl_missingEntry_throws() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-missing-svc-" + uniqueSuffix(),
                        "revoke missing", "owner", "Testing"));

        assertThatThrownBy(() -> commandService.revokeAcl(new RevokeAclCommand(
                skillId, "user", "ghost", "read", "owner")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String uniqueSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
