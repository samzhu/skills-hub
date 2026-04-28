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
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;

/**
 * S016 T4 — Skill aggregate ACL invariants（pure unit；無 Spring Context）。
 *
 * <p>覆蓋 spec §4.11 中 aggregate 業務不變量：
 * <ul>
 *   <li>重複 grant 同一 entry 拋 IllegalStateException</li>
 *   <li>revoke 不存在 entry 拋 IllegalStateException</li>
 *   <li>從 events 重建 aggregate 時 SkillAclGranted/Revoked apply 正確累積/移除</li>
 * </ul>
 */
class SkillAclTest {

    private static final String SKILL_ID = "abc-1";

    @Test
    @DisplayName("AC-9: grantAcl 對新 entry 產生 SkillAclGrantedEvent")
    @Tag("AC-9")
    void grantAcl_newEntry_returnsEvent() {
        var skill = new Skill(SKILL_ID, List.of(createdEvent(1L)));

        var event = skill.grantAcl(new GrantAclCommand(
                SKILL_ID, "group", "engineering", "read", "alice"));

        assertThat(event).isNotNull();
        assertThat(event.aggregateId()).isEqualTo(SKILL_ID);
        assertThat(event.type()).isEqualTo("group");
        assertThat(event.principal()).isEqualTo("engineering");
        assertThat(event.permission()).isEqualTo("read");
        assertThat(event.grantedBy()).isEqualTo("alice");
    }

    @Test
    @DisplayName("AC-9: grantAcl 同一 entry 兩次拋 IllegalStateException（重建 aggregate 已含此 entry 時）")
    @Tag("AC-9")
    void grantAcl_duplicateEntry_throws() {
        var events = List.of(
                createdEvent(1L),
                grantedEvent(2L, "user", "alice", "read", "system"));
        var skill = new Skill(SKILL_ID, events);

        assertThatThrownBy(() -> skill.grantAcl(new GrantAclCommand(
                SKILL_ID, "user", "alice", "read", "alice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user:alice:read");
    }

    @Test
    @DisplayName("AC-10: revokeAcl 對既存 entry 產生 SkillAclRevokedEvent")
    @Tag("AC-10")
    void revokeAcl_existingEntry_returnsEvent() {
        var events = List.of(
                createdEvent(1L),
                grantedEvent(2L, "group", "engineering", "read", "alice"));
        var skill = new Skill(SKILL_ID, events);

        var event = skill.revokeAcl(new RevokeAclCommand(
                SKILL_ID, "group", "engineering", "read", "alice"));

        assertThat(event).isNotNull();
        assertThat(event.aggregateId()).isEqualTo(SKILL_ID);
        assertThat(event.type()).isEqualTo("group");
        assertThat(event.principal()).isEqualTo("engineering");
        assertThat(event.permission()).isEqualTo("read");
        assertThat(event.revokedBy()).isEqualTo("alice");
    }

    @Test
    @DisplayName("AC-10: revokeAcl 不存在 entry 拋 IllegalStateException")
    @Tag("AC-10")
    void revokeAcl_missingEntry_throws() {
        var skill = new Skill(SKILL_ID, List.of(createdEvent(1L)));

        assertThatThrownBy(() -> skill.revokeAcl(new RevokeAclCommand(
                SKILL_ID, "user", "ghost", "read", "alice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user:ghost:read");
    }

    @Test
    @DisplayName("AC-9/10: grant → revoke → grant 同 entry — 重建 aggregate 後第二次 grant 不再拋")
    @Tag("AC-9")
    void grantRevokeGrant_sameEntry_idempotent() {
        var events = List.of(
                createdEvent(1L),
                grantedEvent(2L, "user", "alice", "write", "sys"),
                revokedEvent(3L, "user", "alice", "write", "sys"));
        var skill = new Skill(SKILL_ID, events);

        var event = skill.grantAcl(new GrantAclCommand(
                SKILL_ID, "user", "alice", "write", "sys"));

        assertThat(event.principal()).isEqualTo("alice");
        assertThat(event.permission()).isEqualTo("write");
    }

    @Test
    @DisplayName("AC-9: nextSequence() 跟 ACL events 增長")
    @Tag("AC-9")
    void nextSequence_advancesWithAclEvents() {
        var events = List.of(
                createdEvent(1L),
                grantedEvent(2L, "user", "alice", "read", "sys"),
                grantedEvent(3L, "group", "eng", "read", "sys"));
        var skill = new Skill(SKILL_ID, events);

        assertThat(skill.nextSequence()).isEqualTo(4L);
    }

    // ---- fixture helpers ----

    private DomainEvent createdEvent(long seq) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillCreated",
                Map.of("name", "k", "description", "d", "author", "sys", "category", "Test"),
                seq, Instant.now(), Map.of());
    }

    private DomainEvent grantedEvent(long seq, String type, String principal, String permission, String by) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillAclGranted",
                Map.of("type", type, "principal", principal,
                        "permission", permission, "grantedBy", by),
                seq, Instant.now(), Map.of());
    }

    private DomainEvent revokedEvent(long seq, String type, String principal, String permission, String by) {
        return new DomainEvent(
                "evt-" + seq, SKILL_ID, "Skill", "SkillAclRevoked",
                Map.of("type", type, "principal", principal,
                        "permission", permission, "revokedBy", by),
                seq, Instant.now(), Map.of());
    }
}
