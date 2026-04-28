package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;

/**
 * S018 T1 — Skill aggregate state machine + apply 多型分派 + Suspend/Reactivate 業務方法
 * （pure unit；無 Spring Context）。
 *
 * <p>對應 spec §3 AC-5/6/8/9/10：
 * <ul>
 *   <li>AC-5/6/8/9：呼叫 suspend/reactivate/publishVersion 在錯誤 status 拋 IllegalStateException</li>
 *   <li>AC-10：完整 event 序列重建後 status / publishedVersions / nextSequence 正確</li>
 * </ul>
 */
class SkillStateMachineTest {

    private static final String SKILL_ID = "abc-1";

    @Test
    @DisplayName("AC-10: SkillCreated → status = DRAFT")
    @Tag("AC-10")
    void replay_skillCreated_givesDraftStatus() {
        var skill = new Skill(SKILL_ID, List.of(createdEvent(1L)));
        assertThat(skill.status()).isEqualTo(SkillStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-10: first SkillVersionPublished → status = PUBLISHED")
    @Tag("AC-10")
    void replay_firstVersionPublished_givesPublishedStatus() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0")));
        assertThat(skill.status()).isEqualTo(SkillStatus.PUBLISHED);
    }

    @Test
    @DisplayName("AC-10: 完整 event 序列 — Created → 2 Publishes → Suspended → Reactivated → status=PUBLISHED")
    @Tag("AC-10")
    void replay_fullSequence_endsAtPublished() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0"),
                versionPublishedEvent(3L, "1.1.0"),
                suspendedEvent(4L),
                reactivatedEvent(5L)));

        assertThat(skill.status()).isEqualTo(SkillStatus.PUBLISHED);
        assertThat(skill.nextSequence()).isEqualTo(6L);
    }

    @Test
    @DisplayName("AC-5: DRAFT.suspend() throws — aggregate propagate")
    @Tag("AC-5")
    void aggregate_suspendDraft_throws() {
        var skill = new Skill(SKILL_ID, List.of(createdEvent(1L)));
        assertThatThrownBy(() -> skill.suspend(new SuspendCommand(SKILL_ID, "violation", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("AC-6: SUSPENDED.suspend() throws — already suspended")
    @Tag("AC-6")
    void aggregate_suspendAlreadySuspended_throws() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0"),
                suspendedEvent(3L)));
        assertThatThrownBy(() -> skill.suspend(new SuspendCommand(SKILL_ID, "duplicate", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("AC-8: PUBLISHED.reactivate() throws — not in SUSPENDED")
    @Tag("AC-8")
    void aggregate_reactivatePublished_throws() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0")));
        assertThatThrownBy(() -> skill.reactivate(new ReactivateCommand(SKILL_ID, "manual review")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    @DisplayName("AC-9: SUSPENDED.publishVersion() throws — cannot publish while suspended")
    @Tag("AC-9")
    void aggregate_publishVersionWhileSuspended_throws() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0"),
                suspendedEvent(3L)));
        assertThatThrownBy(() -> skill.publishVersion("2.0.0", "path", 100, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("Happy: PUBLISHED.suspend() returns SkillSuspendedEvent")
    @Tag("AC-4")
    void aggregate_suspendPublished_returnsEvent() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0")));

        var event = skill.suspend(new SuspendCommand(SKILL_ID, "policy violation", "admin-user"));

        assertThat(event).isNotNull();
        assertThat(event.aggregateId()).isEqualTo(SKILL_ID);
        assertThat(event.reason()).isEqualTo("policy violation");
        assertThat(event.suspendedBy()).isEqualTo("admin-user");
    }

    @Test
    @DisplayName("Happy: SUSPENDED.reactivate() returns SkillReactivatedEvent")
    @Tag("AC-7")
    void aggregate_reactivateSuspended_returnsEvent() {
        var skill = new Skill(SKILL_ID, List.of(
                createdEvent(1L),
                versionPublishedEvent(2L, "1.0.0"),
                suspendedEvent(3L)));

        var event = skill.reactivate(new ReactivateCommand(SKILL_ID, "manual review approved"));

        assertThat(event).isNotNull();
        assertThat(event.aggregateId()).isEqualTo(SKILL_ID);
        assertThat(event.reason()).isEqualTo("manual review approved");
    }

    // ---- fixture helpers ----

    private DomainEvent createdEvent(long seq) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillCreated",
                Map.of("name", "k", "description", "d", "author", "sys", "category", "T"),
                seq, Instant.now(), Map.of());
    }

    private DomainEvent versionPublishedEvent(long seq, String version) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillVersionPublished",
                Map.of("version", version, "storagePath", "p", "fileSize", 0L),
                seq, Instant.now(), Map.of());
    }

    private DomainEvent suspendedEvent(long seq) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillSuspended",
                Map.of("reason", "policy violation", "suspendedBy", "admin"),
                seq, Instant.now(), Map.of());
    }

    private DomainEvent reactivatedEvent(long seq) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillReactivated",
                Map.of("reason", "manual review approved"),
                seq, Instant.now(), Map.of());
    }
}
