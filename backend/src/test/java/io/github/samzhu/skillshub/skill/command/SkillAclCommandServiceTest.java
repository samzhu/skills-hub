package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 T4 — SkillCommandService.grantAcl / revokeAcl 整合測試。
 *
 * <p>對應 spec §4.11。S024 T05B：
 * <ul>
 *   <li>aggregate state（aclEntries）由 SkillRepository 同步驗證</li>
 *   <li>audit row 由 AuditEventListener async 寫入 — Awaitility 等候</li>
 * </ul>
 *
 * <p>S025b T02 — <b>deviation from spec REPO migration target</b>：本 test 跨 skill +
 * audit module（commandService 寫入 → outbox publish → AuditEventListener 跨 module 訂閱
 * 寫 audit row）；{@code @ApplicationModuleTest(skill, DIRECT_DEPENDENCIES)} 不載 audit consumer
 * （audit imports skill events 為單向依賴），async assertion 必失敗。保留 {@code @SpringBootTest}
 * 為跨 module event-driven 整合測試的合理 CONFIG bucket（per S025a {@code AuditEventListenerTest}
 * 採 audit-side test 模式：在 audit module 內用 {@code Scenario.publish(event)} 直接觸發
 * listener，繞過 skill module 的 commandService 鏈）。記入 §7 deviation。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillAclCommandServiceTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private DomainEventRepository eventStore;

    @Test
    @DisplayName("AC-9: grantAcl → aclEntries 含新 entry + SkillAclGranted audit")
    @Tag("AC-9")
    void grantAcl_persistsAggregateAndAudit() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-grant-svc-" + uniqueSuffix(),
                        "grant via service", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "group", "engineering", "read", "owner"));

        // sync — aggregate state 直接讀
        assertThat(skillRepo.findById(skillId).orElseThrow().getAclEntries())
                .contains("group:engineering:read");

        // async audit
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
            var granted = events.stream()
                    .filter(e -> "SkillAclGranted".equals(e.eventType())
                            && "engineering".equals(e.payload().get("principal")))
                    .findFirst();
            assertThat(granted).isPresent();
            assertThat(granted.get().payload()).containsEntry("type", "group");
            assertThat(granted.get().payload()).containsEntry("permission", "read");
            assertThat(granted.get().payload()).containsEntry("grantedBy", "owner");
        });
    }

    @Test
    @DisplayName("AC-10: revokeAcl 移除既存 entry → SkillAclRevoked audit")
    @Tag("AC-10")
    void revokeAcl_persistsAggregateAndAudit() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-revoke-svc-" + uniqueSuffix(),
                        "revoke via service", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "group", "engineering", "read", "owner"));
        commandService.revokeAcl(new RevokeAclCommand(
                skillId, "group", "engineering", "read", "owner"));

        assertThat(skillRepo.findById(skillId).orElseThrow().getAclEntries())
                .doesNotContain("group:engineering:read");

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
            var revoked = events.stream()
                    .filter(e -> "SkillAclRevoked".equals(e.eventType())
                            && "engineering".equals(e.payload().get("principal")))
                    .findFirst();
            assertThat(revoked).isPresent();
            assertThat(revoked.get().payload()).containsEntry("revokedBy", "owner");
        });
    }

    @Test
    @DisplayName("AC-9: 重複 grant 同 entry → IllegalStateException + aggregate 不變")
    @Tag("AC-9")
    void grantAcl_duplicateEntry_throwsAndDoesNotPersist() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("acl-dup-svc-" + uniqueSuffix(),
                        "duplicate grant", "owner", "Testing"));

        commandService.grantAcl(new GrantAclCommand(
                skillId, "user", "alice", "read", "owner"));
        var entriesBefore = skillRepo.findById(skillId).orElseThrow().getAclEntries();

        assertThatThrownBy(() -> commandService.grantAcl(new GrantAclCommand(
                skillId, "user", "alice", "read", "owner")))
                .isInstanceOf(IllegalStateException.class);

        // aggregate state 不變
        assertThat(skillRepo.findById(skillId).orElseThrow().getAclEntries())
                .containsExactlyInAnyOrderElementsOf(entriesBefore);
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
