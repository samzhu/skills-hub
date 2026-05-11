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
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;

/**
 * S024 T1 + T2 — Skill aggregate 充血聚合 unit test 集合（合併原 T1 bootstrap test）。
 *
 * <p>純 unit；無 Spring；快速；不汙染 context cache key。覆蓋：
 * <ul>
 *   <li>T1 AC-2 partial — {@link Skill#create(CreateSkillCommand)} factory + S016 owner ACL seed +
 *       {@link Skill#recordVersionPublished(String)} state transition</li>
 *   <li>T2 AC-2 full — recordSuspended / recordReactivated / recordDownload mutate state + register events</li>
 *   <li>T2 AC-6 — {@link Skill#recordVersionPublished} on SUSPENDED throws state machine guard</li>
 *   <li>T2 AC-8 — {@link Skill#getAclEntries()} returns unmodifiable view（read-side immutability）。
 *       S167b 後 grantAcl/revokeAcl aggregate 充血方法已移除，寫 ACL 改走 S114a SkillGrantService。</li>
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
        // S026 + S114a: 加 "public:*:read" public-read entry — skill 預設對所有使用者開放讀取
        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete", "public:*:read");

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
    @DisplayName("AC-2 partial: Skill.create(null name) 拋 IllegalArgumentException")
    void skillCreateRejectsNullName() {
        // S054: 從 NPE 改為 IAE — 走 GlobalExceptionHandler 既有 400 VALIDATION_ERROR 路徑
        assertThatThrownBy(() -> Skill.create(new CreateSkillCommand(null, "desc", "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
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
    @DisplayName("AC-S041: author null（caller 顯式不傳）→ 允許，ACL 只 seed public:*:read（測試 fixture 用）")
    void create_nullAuthor_seedsPublicReadOnly() {
        var skill = Skill.create(new CreateSkillCommand("null-author-test", "desc", null, "DevOps"));

        assertThat(skill.getAuthor()).isNull();
        assertThat(skill.getAclEntries()).containsExactly("public:*:read");
    }

    @Test
    @Tag("AC-S041")
    @DisplayName("AC-S041: name 含前後空白 → trim 後驗證並儲存 trimmed")
    void create_nameWithWhitespace_trimmedAndStored() {
        var skill = Skill.create(new CreateSkillCommand("  valid-name  ", "desc", "alice", "DevOps"));

        assertThat(skill.getName()).isEqualTo("valid-name");
    }

    // ============================================================================
    // S042 — description / category invariant validation
    // ============================================================================

    @Test
    @Tag("AC-S042")
    @DisplayName("AC-S042: description 空字串 → IllegalArgumentException")
    void create_emptyDescription_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("valid-name", "", "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description must not be blank");
    }

    @Test
    @Tag("AC-S042")
    @DisplayName("AC-S042: description 全空白 → IllegalArgumentException")
    void create_blankDescription_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("valid-name", "   ", "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description must not be blank");
    }

    @Test
    @Tag("AC-S042")
    @DisplayName("AC-S042: description >1024 chars → IllegalArgumentException")
    void create_longDescription_throws() {
        var longDesc = "a".repeat(1025);
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("valid-name", longDesc, "alice", "DevOps")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 1024 characters");
    }

    @Test
    @Tag("AC-S042")
    @DisplayName("AC-S042: category 空白 → IllegalArgumentException")
    void create_blankCategory_throws() {
        assertThatThrownBy(() -> Skill.create(
                new CreateSkillCommand("valid-name", "desc", "alice", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category must not be blank");
    }

    // ============================================================================
    // S116 — Visibility public/private toggle
    // ============================================================================

    @Test
    @Tag("AC-S116-1")
    @DisplayName("S116 AC-1: PUBLIC visibility (or 4-arg backward-compat) → acl_entries 含 public:*:read")
    void s116_publicVisibility_includesPublicReadEntry() {
        // 4-arg backward-compat ctor delegates to 5-arg PUBLIC default
        var skill = Skill.create(new CreateSkillCommand("public-skill", "desc", "alice", "DevOps"));

        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete", "public:*:read");
    }

    @Test
    @Tag("AC-S116-2")
    @DisplayName("S116 AC-2: PRIVATE visibility → acl_entries 不含 public:*:read")
    void s116_privateVisibility_excludesPublicReadEntry() {
        var skill = Skill.create(new CreateSkillCommand(
                "private-skill", "desc", "alice", "DevOps",
                io.github.samzhu.skillshub.skill.domain.Visibility.PRIVATE));

        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete");
        assertThat(skill.getAclEntries()).doesNotContain("public:*:read");
    }

    @Test
    @Tag("AC-S116-2")
    @DisplayName("S116 AC-2 explicit: PUBLIC visibility 顯式傳入 → 同 4-arg backward-compat 行為")
    void s116_explicitPublic_sameAsDefault() {
        var skill = Skill.create(new CreateSkillCommand(
                "public-skill", "desc", "alice", "DevOps",
                io.github.samzhu.skillshub.skill.domain.Visibility.PUBLIC));

        assertThat(skill.getAclEntries()).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete", "public:*:read");
    }

    @Test
    @Tag("AC-S116-8")
    @DisplayName("S116 AC-8: PRIVATE + author=null → IllegalArgumentException（無 owner 不可 private）")
    void s116_privateWithoutAuthor_throws() {
        assertThatThrownBy(() -> Skill.create(new CreateSkillCommand(
                "no-author", "desc", null, "DevOps",
                io.github.samzhu.skillshub.skill.domain.Visibility.PRIVATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIVATE 必須提供 author");
    }

    @Test
    @Tag("AC-S116-1")
    @DisplayName("S116 AC-1 corner: PUBLIC + author=null → 仍含 public:*:read（無 owner ACL 但公開讀取）")
    void s116_publicWithoutAuthor_seedsOnlyPublicRead() {
        var skill = Skill.create(new CreateSkillCommand(
                "no-author", "desc", null, "DevOps",
                io.github.samzhu.skillshub.skill.domain.Visibility.PUBLIC));

        assertThat(skill.getAclEntries()).containsExactly("public:*:read");
    }

    @Test
    @Tag("AC-S144-1")
    @DisplayName("AC-S144-1: markDeleted registers SkillDeletedEvent with deletedBy + storage paths")
    void markDeletedRegistersSkillDeletedEvent() {
        var skill = Skill.create(new CreateSkillCommand("delete-event", "desc", "alice", "DevOps"));
        clearDomainEvents(skill);

        skill.markDeleted("alice", List.of(
                "skills/%s/1.0.0/skill.zip".formatted(skill.getId()),
                "skills/%s/1.1.0/skill.zip".formatted(skill.getId())));

        var events = retrieveDomainEvents(skill);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillDeletedEvent.class);
        var event = (SkillDeletedEvent) events.iterator().next();
        assertThat(event.aggregateId()).isEqualTo(skill.getId());
        assertThat(event.name()).isEqualTo("delete-event");
        assertThat(event.deletedBy()).isEqualTo("alice");
        assertThat(event.deletedAt()).isNotNull();
        assertThat(event.storagePaths()).containsExactly(
                "skills/%s/1.0.0/skill.zip".formatted(skill.getId()),
                "skills/%s/1.1.0/skill.zip".formatted(skill.getId()));
    }
}
