package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.api.SkillSuspendedException;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;
import io.github.samzhu.skillshub.skill.query.SkillQueryService;

/**
 * S018 T2 — SkillCommandService.suspend / reactivate 整合測試。
 *
 * <p>對應 spec §3 AC-4 / AC-7。S024 T05B：
 * <ul>
 *   <li>aggregate state 變化（SUSPENDED / PUBLISHED）由 {@link SkillRepository#findById}
 *       同步驗證（{@code skills} 表 commit 後即可讀）</li>
 *   <li>audit row 由 AuditEventListener async 寫入 — 用 Awaitility 等候</li>
 * </ul>
 *
 * <p>S025b T02 — <b>deviation from spec REPO migration target</b>：同 {@link SkillAclCommandServiceTest}
 * 場景，cross-module 整合（skill commandService → outbox → audit listener）；slice / module
 * 範圍皆無法包含跨 module 的 listener consumer，保留 {@code @SpringBootTest}。記入 §7。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillSuspendReactivateTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private DomainEventRepository eventStore;

    @Autowired
    private SkillQueryService queryService;

    @Test
    @DisplayName("AC-4: PUBLISHED skill suspend → aggregate SUSPENDED + SkillSuspended audit")
    @Tag("AC-4")
    void suspendPublishedSkill_persistsEvent() {
        var skillId = createPublishedSkill();

        commandService.suspend(new SuspendCommand(skillId, "policy violation", "admin-user"));

        // sync state — aggregate 改為 SUSPENDED
        assertThat(loadSkill(skillId).getStatus()).isEqualTo(SkillStatus.SUSPENDED);

        // async audit
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
            var suspended = events.stream()
                    .filter(e -> "SkillSuspended".equals(e.eventType())).findFirst();
            assertThat(suspended).isPresent();
            assertThat(suspended.get().aggregateType()).isEqualTo("Skill");
            assertThat(suspended.get().payload()).containsEntry("reason", "policy violation");
            assertThat(suspended.get().payload()).containsEntry("suspendedBy", "admin-user");
        });
    }

    @Test
    @DisplayName("AC-7: SUSPENDED skill reactivate → aggregate PUBLISHED + SkillReactivated audit")
    @Tag("AC-7")
    void reactivateSuspendedSkill_persistsEvent() {
        var skillId = createPublishedSkill();
        commandService.suspend(new SuspendCommand(skillId, "violation", "admin"));

        commandService.reactivate(new ReactivateCommand(skillId, "manual review approved"));

        assertThat(loadSkill(skillId).getStatus()).isEqualTo(SkillStatus.PUBLISHED);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
            var reactivated = events.stream()
                    .filter(e -> "SkillReactivated".equals(e.eventType())).findFirst();
            assertThat(reactivated).isPresent();
            assertThat(reactivated.get().payload()).containsEntry("reason", "manual review approved");
        });
    }

    @Test
    @DisplayName("AC-5 (service): DRAFT skill suspend → IllegalStateException + state 不變")
    @Tag("AC-5")
    void suspendDraftSkill_throwsAndDoesNotPersist() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("draft-suspend-" + uniqueSuffix(),
                        "DRAFT skill", "owner", "Testing"));
        assertThat(loadSkill(skillId).getStatus()).isEqualTo(SkillStatus.DRAFT);

        assertThatThrownBy(() -> commandService.suspend(
                new SuspendCommand(skillId, "...", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");

        // aggregate state 仍為 DRAFT
        assertThat(loadSkill(skillId).getStatus()).isEqualTo(SkillStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-S029: SUSPENDED skill download → SkillSuspendedException（fail-fast 早於 storage download）")
    @Tag("AC-S029")
    void suspendedSkillBlocksDownload() {
        var skillId = createPublishedSkill();
        commandService.suspend(new SuspendCommand(skillId, "policy violation", "admin-user"));
        assertThat(loadSkill(skillId).getStatus()).isEqualTo(SkillStatus.SUSPENDED);

        // SUSPENDED → 拒絕下載；S029 guard 在 storage.download 之前觸發，
        // 因此即使 storagePath 為 fixture 的虛擬路徑（"gs://bucket/p"）也不會走到 storage layer。
        assertThatThrownBy(() -> queryService.downloadLatest(skillId))
                .isInstanceOf(SkillSuspendedException.class)
                .hasMessageContaining(skillId);
        assertThatThrownBy(() -> queryService.downloadVersion(skillId, "1.0.0"))
                .isInstanceOf(SkillSuspendedException.class);
    }

    private Skill loadSkill(String skillId) {
        return skillRepo.findById(skillId).orElseThrow();
    }

    private String createPublishedSkill() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("published-" + uniqueSuffix(),
                        "test fixture", "owner", "Testing"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://bucket/p", 0L, Map.of()));
        return skillId;
    }

    private static String uniqueSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
