package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * S018 T2 — SkillCommandService.suspend / reactivate 整合測試。
 *
 * <p>對應 spec §3 AC-4 / AC-7：service 走 ES saveAndPublish 路徑寫 SkillSuspended /
 * SkillReactivated event；@Transactional 確保失敗時 event store 不變。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillSuspendReactivateTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private DomainEventRepository eventStore;

    @Test
    @DisplayName("AC-4: PUBLISHED skill suspend → eventStore 含 SkillSuspended sequence=N+1")
    @Tag("AC-4")
    void suspendPublishedSkill_persistsEvent() {
        var skillId = createPublishedSkill();
        var beforeCount = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();

        commandService.suspend(new SuspendCommand(skillId, "policy violation", "admin-user"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).hasSize(beforeCount + 1);

        var suspendedEvent = events.get(events.size() - 1);
        assertThat(suspendedEvent.eventType()).isEqualTo("SkillSuspended");
        assertThat(suspendedEvent.aggregateType()).isEqualTo("Skill");
        assertThat(suspendedEvent.payload()).containsEntry("reason", "policy violation");
        assertThat(suspendedEvent.payload()).containsEntry("suspendedBy", "admin-user");
    }

    @Test
    @DisplayName("AC-7: SUSPENDED skill reactivate → eventStore 含 SkillReactivated")
    @Tag("AC-7")
    void reactivateSuspendedSkill_persistsEvent() {
        var skillId = createPublishedSkill();
        commandService.suspend(new SuspendCommand(skillId, "violation", "admin"));

        commandService.reactivate(new ReactivateCommand(skillId, "manual review approved"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        var reactivatedEvent = events.get(events.size() - 1);
        assertThat(reactivatedEvent.eventType()).isEqualTo("SkillReactivated");
        assertThat(reactivatedEvent.payload()).containsEntry("reason", "manual review approved");
    }

    @Test
    @DisplayName("AC-5 (service): DRAFT skill suspend → IllegalStateException + event store 不變")
    @Tag("AC-5")
    void suspendDraftSkill_throwsAndDoesNotPersist() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("draft-suspend-" + uniqueSuffix(),
                        "DRAFT skill", "owner", "Testing"));
        var beforeCount = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();

        assertThatThrownBy(() -> commandService.suspend(
                new SuspendCommand(skillId, "...", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");

        var afterCount = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();
        assertThat(afterCount).isEqualTo(beforeCount);
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
