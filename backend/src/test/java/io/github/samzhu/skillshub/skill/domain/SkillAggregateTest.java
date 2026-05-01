package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AbstractAggregateRoot;

import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;

/**
 * S024 T1 + T2 — Skill aggregate 充血聚合 unit test 集合（合併原 T1 bootstrap test）。
 *
 * <p>純 unit；無 Spring；快速；不汙染 context cache key。覆蓋：
 * <ul>
 *   <li>T1 AC-2 partial — {@link Skill#create(CreateSkillCommand)} factory + S016 owner ACL seed +
 *       {@link Skill#recordVersionPublished(String)} state transition</li>
 *   <li>T2 AC-2 full — recordSuspended / recordReactivated / recordAclGranted / recordAclRevoked /
 *       recordDownload mutate state + register events</li>
 *   <li>T2 AC-6 — {@link Skill#recordVersionPublished} on SUSPENDED throws state machine guard</li>
 *   <li>T2 AC-8 — recordAclGranted / recordAclRevoked 業務不變量檢查（重複 grant / 不存在 revoke）</li>
 * </ul>
 */
class SkillAggregateTest {

    // ============================================================================
    // T1 — Skill.create + recordVersionPublished
    // ============================================================================

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2 partial: Skill.create(CreateSkillCommand) 設定 state + register SkillCreatedEvent + S016 seed owner ACL")
    void skillCreateFactorySetsStateAndRegistersEvent() {
        var skill = Skill.create(new CreateSkillCommand("docker-helper", "Compose helper", "alice", "DevOps"));

        assertThat(skill.getId()).isNotBlank();
        assertThat(skill.getName()).isEqualTo("docker-helper");
        assertThat(skill.getDescription()).isEqualTo("Compose helper");
        assertThat(skill.getAuthor()).isEqualTo("alice");
        assertThat(skill.getCategory()).isEqualTo("DevOps");
        assertThat(skill.getStatus()).isEqualTo(SkillStatus.DRAFT);
        assertThat(skill.getLatestVersion()).isNull();
        assertThat(skill.getRiskLevel()).isNull();
        assertThat(skill.getDownloadCount()).isZero();
        assertThat(skill.getCreatedAt()).isNotNull();
        assertThat(skill.getUpdatedAt()).isNotNull();
        // version=null → Persistable.isNew()=true（factory 出來尚未持久化；INSERT 後寫回 0）
        assertThat(skill.getVersion()).isNull();
        assertThat(skill.isNew()).isTrue();

        // S016 自動 seed author 為 owner — 與 legacy SkillProjection.on(SkillCreatedEvent) 同邏輯
        // S026: 加 "*:read" public-read pseudo-principal — skill 預設對所有使用者開放讀取
        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete", "*:read");

        Collection<Object> events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillCreatedEvent.class);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2 partial: recordVersionPublished on DRAFT → PUBLISHED + register SkillVersionPublishedFromAggregate")
    void recordVersionPublishedOnDraftTransitionsAndRegistersEvent() {
        var skill = Skill.create(new CreateSkillCommand("k8s-deploy", "K8s helper", "alice", "DevOps"));
        clearDomainEvents(skill);

        skill.recordVersionPublished("1.0.0");

        assertThat(skill.getStatus()).isEqualTo(SkillStatus.PUBLISHED);
        assertThat(skill.getLatestVersion()).isEqualTo("1.0.0");
        Collection<Object> events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillVersionPublishedFromAggregate.class);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2 partial: Skill.create(null name) 拋 NullPointerException")
    void skillCreateRejectsNullName() {
        assertThatThrownBy(() -> Skill.create(new CreateSkillCommand(null, "desc", "alice", "DevOps")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    // ============================================================================
    // T2 — recordSuspended / recordReactivated
    // ============================================================================

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: recordSuspended on PUBLISHED → status=SUSPENDED + register SkillSuspendedEvent")
    void recordSuspendedOnPublished() {
        var skill = Skill.create(new CreateSkillCommand("susp-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");   // DRAFT → PUBLISHED
        clearDomainEvents(skill);

        skill.suspend(new SuspendCommand(skill.getId(), "policy violation", "admin"));

        assertThat(skill.getStatus()).isEqualTo(SkillStatus.SUSPENDED);
        assertThat(skill.getUpdatedAt()).isNotNull();
        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillSuspendedEvent.class);
        var evt = (SkillSuspendedEvent) events.iterator().next();
        assertThat(evt.aggregateId()).isEqualTo(skill.getId());
        assertThat(evt.reason()).isEqualTo("policy violation");
        assertThat(evt.suspendedBy()).isEqualTo("admin");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: recordReactivated on SUSPENDED → status=PUBLISHED + register SkillReactivatedEvent")
    void recordReactivatedOnSuspended() {
        var skill = Skill.create(new CreateSkillCommand("reactivate-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        skill.suspend(new SuspendCommand(skill.getId(), "policy", "admin"));
        clearDomainEvents(skill);

        skill.reactivate(new ReactivateCommand(skill.getId(), "manual review approved"));

        assertThat(skill.getStatus()).isEqualTo(SkillStatus.PUBLISHED);
        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillReactivatedEvent.class);
        var evt = (SkillReactivatedEvent) events.iterator().next();
        assertThat(evt.reason()).isEqualTo("manual review approved");
    }

    // ============================================================================
    // T2 — AC-6: state machine guard for publishVersion on SUSPENDED
    // ============================================================================

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: recordVersionPublished on SUSPENDED → IllegalStateException + state 不變")
    void recordVersionPublishedOnSuspendedThrows() {
        var skill = Skill.create(new CreateSkillCommand("suspended-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        skill.suspend(new SuspendCommand(skill.getId(), "policy", "admin"));
        clearDomainEvents(skill);

        assertThatThrownBy(() -> skill.recordVersionPublished("1.1.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");

        // state 不變
        assertThat(skill.getStatus()).isEqualTo(SkillStatus.SUSPENDED);
        assertThat(skill.getLatestVersion()).isEqualTo("1.0.0");
        // 沒新 event
        assertThat(retrieveDomainEvents(skill)).isEmpty();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: recordSuspended on DRAFT → IllegalStateException（DRAFT 尚未發版不可 suspend）")
    void recordSuspendedOnDraftThrows() {
        var skill = Skill.create(new CreateSkillCommand("draft-suspend", "desc", "alice", "DevOps"));
        clearDomainEvents(skill);

        assertThatThrownBy(() -> skill.suspend(new SuspendCommand(skill.getId(), "premature", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");

        assertThat(skill.getStatus()).isEqualTo(SkillStatus.DRAFT);
        assertThat(retrieveDomainEvents(skill)).isEmpty();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: recordReactivated on PUBLISHED → IllegalStateException（PUBLISHED 不需重啟）")
    void recordReactivatedOnPublishedThrows() {
        var skill = Skill.create(new CreateSkillCommand("publish-reactivate", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        clearDomainEvents(skill);

        assertThatThrownBy(() -> skill.reactivate(new ReactivateCommand(skill.getId(), "ineffective")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");

        assertThat(skill.getStatus()).isEqualTo(SkillStatus.PUBLISHED);
    }

    // ============================================================================
    // T2 — AC-8: recordAclGranted / recordAclRevoked
    // ============================================================================

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: recordAclGranted append entry to aclEntries + register SkillAclGrantedEvent")
    void recordAclGrantedAppendsAndRegistersEvent() {
        // 用 null author 避免 S016 自動 seed user-namespace ACL 干擾本 test
        // S026: null author 仍 seed "*:read" public-read pseudo-principal
        var skill = Skill.create(new CreateSkillCommand("acl-grant-test", "desc", null, "DevOps"));
        assertThat(skill.getAclEntries()).containsExactly("*:read");
        clearDomainEvents(skill);

        skill.grantAcl(new GrantAclCommand(skill.getId(), "user", "alice", "read", "admin"));

        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder("*:read", "user:alice:read");
        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillAclGrantedEvent.class);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: grantAcl 重複 entry → IllegalStateException + state 不變")
    void grantAclDuplicateThrows() {
        var skill = Skill.create(new CreateSkillCommand("acl-dup-test", "desc", null, "DevOps"));
        skill.grantAcl(new GrantAclCommand(skill.getId(), "user", "alice", "read", "admin"));
        clearDomainEvents(skill);

        assertThatThrownBy(() -> skill.grantAcl(
                new GrantAclCommand(skill.getId(), "user", "alice", "read", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        // aclEntries 不變（S026: null author 預設含 "*:read"）
        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder("*:read", "user:alice:read");
        assertThat(retrieveDomainEvents(skill)).isEmpty();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: revokeAcl 移除 entry + register SkillAclRevokedEvent")
    void revokeAclRemovesAndRegistersEvent() {
        var skill = Skill.create(new CreateSkillCommand("acl-revoke-test", "desc", null, "DevOps"));
        skill.grantAcl(new GrantAclCommand(skill.getId(), "user", "alice", "read", "admin"));
        clearDomainEvents(skill);

        skill.revokeAcl(new RevokeAclCommand(skill.getId(), "user", "alice", "read", "admin"));

        // S026: null author 預設 seed "*:read"；alice grant 後 revoke 只移 user:alice:read，"*:read" 保留
        assertThat(skill.getAclEntries()).containsExactly("*:read");
        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillAclRevokedEvent.class);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: revokeAcl entry 不存在 → IllegalStateException")
    void revokeAclNotFoundThrows() {
        var skill = Skill.create(new CreateSkillCommand("acl-nonex-test", "desc", null, "DevOps"));
        clearDomainEvents(skill);

        assertThatThrownBy(() -> skill.revokeAcl(
                new RevokeAclCommand(skill.getId(), "user", "alice", "read", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");

        // S026: null author 預設 seed "*:read"；revoke 失敗後狀態維持
        assertThat(skill.getAclEntries()).containsExactly("*:read");
        assertThat(retrieveDomainEvents(skill)).isEmpty();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: getAclEntries() 回不可變 view（外部 mutate 不影響 aggregate state）")
    void getAclEntriesReturnsImmutableCopy() {
        var skill = Skill.create(new CreateSkillCommand("acl-immutable", "desc", "alice", "DevOps"));
        List<String> external = skill.getAclEntries();

        // List.copyOf 回的 unmodifiable list — 嘗試 mutate 應拋
        assertThatThrownBy(() -> external.add("user:bob:read"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ============================================================================
    // T2 — recordDownload
    // ============================================================================

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: recordDownload increment downloadCount + register SkillDownloadedEvent")
    void recordDownloadIncrementsCountAndRegistersEvent() {
        var skill = Skill.create(new CreateSkillCommand("download-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        clearDomainEvents(skill);
        long beforeCount = skill.getDownloadCount();

        skill.recordDownload();

        assertThat(skill.getDownloadCount()).isEqualTo(beforeCount + 1);
        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillDownloadedEvent.class);
        var evt = (SkillDownloadedEvent) events.iterator().next();
        assertThat(evt.aggregateId()).isEqualTo(skill.getId());
        assertThat(evt.version()).isEqualTo("1.0.0");
        assertThat(evt.eventId()).isNotBlank();   // factory 自動產生 UUID
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: recordDownload 多次累計 — atomic state mutation")
    void recordDownloadMultipleTimes() {
        var skill = Skill.create(new CreateSkillCommand("download-mult", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");

        skill.recordDownload();
        skill.recordDownload();
        skill.recordDownload();

        assertThat(skill.getDownloadCount()).isEqualTo(3);
    }

    // ============================================================================
    // Reflection helpers — AbstractAggregateRoot.domainEvents() / clearDomainEvents() are protected
    // ============================================================================

    @SuppressWarnings("unchecked")
    private static Collection<Object> retrieveDomainEvents(Skill skill) {
        try {
            var method = AbstractAggregateRoot.class.getDeclaredMethod("domainEvents");
            method.setAccessible(true);
            return (Collection<Object>) method.invoke(skill);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to invoke domainEvents() via reflection", e);
        }
    }

    private static void clearDomainEvents(Skill skill) {
        try {
            var method = AbstractAggregateRoot.class.getDeclaredMethod("clearDomainEvents");
            method.setAccessible(true);
            method.invoke(skill);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to invoke clearDomainEvents() via reflection", e);
        }
    }

    // ============================================================================
    // S041 — Skill.create input invariant validation
    // ============================================================================

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: name 違反 agentskills.io regex（uppercase）→ IllegalArgumentException")
    void create_invalidName_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("BadName", "desc", "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match")
                .hasMessageContaining("BadName");
    }

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: name 為空字串 → IllegalArgumentException")
    void create_emptyName_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("", "desc", "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: author 空白「   」trim 後為 blank → IllegalArgumentException（避免 ACL user: :read 畸形）")
    void create_blankAuthor_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("blank-author-test", "desc", "   ", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: author null（caller 顯式不傳）→ 允許，ACL 只 seed *:read（測試 fixture 用）")
    void create_nullAuthor_seedsPublicReadOnly() {
        var skill = Skill.create(new CreateSkillCommand("null-author-test", "desc", null, "DevOps"));

        assertThat(skill.getAuthor()).isNull();
        assertThat(skill.getAclEntries()).containsExactly("*:read");
    }

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: name 含前後空白 → trim 後驗證並儲存 trimmed")
    void create_nameWithWhitespace_trimmedAndStored() {
        var skill = Skill.create(new CreateSkillCommand("  valid-name  ", "desc", "alice", "DevOps"));

        assertThat(skill.getName()).isEqualTo("valid-name");
    }
}
