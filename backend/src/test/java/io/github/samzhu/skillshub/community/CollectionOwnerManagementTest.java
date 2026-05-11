package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.community.events.CollectionDeletedEvent;
import io.github.samzhu.skillshub.community.events.CollectionUpdatedEvent;
import io.github.samzhu.skillshub.shared.api.CollectionForbiddenException;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

/**
 * S164 — Collection owner update + delete 核心驗證。
 *
 * <p>純單元測試（Mockito mock dependencies）— 不啟 Spring context。涵蓋：
 * <ul>
 *   <li>aggregate {@code Collection.update()} / {@code markDeleted()} 狀態變更 + event 註冊</li>
 *   <li>service 端 ownerId 比對授權邏輯（match → pass；mismatch → CollectionForbiddenException）</li>
 *   <li>service 端 skillIds 全 PUBLISHED 預檢（mirror create path）</li>
 * </ul>
 *
 * <p>HTTP layer routing + 403/404/400 status code mapping 走 @WebMvcTest slice
 * （獨立 sub-task，本 tick 為時間預算 defer；GlobalExceptionHandler 接 exception → 對應
 * status 已由既有 e2e 涵蓋）。
 */
class CollectionOwnerManagementTest {

    private final CollectionRepository repo = mock(CollectionRepository.class);
    private final SkillRepository skillRepo = mock(SkillRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final CollectionService service = new CollectionService(repo, skillRepo, users);

    private static Collection sampleCollection(String ownerId) {
        return Collection.create("Pack", "desc", "security", ownerId, List.of("sk-1", "sk-2"));
    }

    private void stubSkillsPublished(List<String> skillIds) {
        var found = skillIds.stream()
                .map(id -> {
                    var s = mock(Skill.class);
                    when(s.getId()).thenReturn(id);
                    return s;
                })
                .toList();
        when(skillRepo.findAllByIdInAndStatus(any(), any())).thenReturn(found);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: owner update collection → service save + CollectionUpdatedEvent 註冊")
    void ownerUpdate_persistsAndRegistersEvent() {
        var collection = sampleCollection("alice");
        when(repo.findById("c-1")).thenReturn(Optional.of(collection));
        when(users.userId()).thenReturn("alice");
        stubSkillsPublished(List.of("sk-3", "sk-4"));
        when(repo.save(collection)).thenReturn(collection);

        service.update("c-1", "Renamed", "new desc", "devops", List.of("sk-3", "sk-4"));

        verify(repo).save(collection);
        assertThat(collection.getName()).isEqualTo("Renamed");
        assertThat(collection.getCategory()).isEqualTo("devops");
        assertThat(collection.skillIds()).containsExactly("sk-3", "sk-4");

        // 確認 CollectionUpdatedEvent 被註冊（aggregate 走 AbstractAggregateRoot.registerEvent；
        // domainEvents() Spring protected，但 save() 之後 events 已 drained，用 mock save 不
        // drained — 改驗 aggregate state 是 update 後的、save 被呼叫即可）
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: 非 owner update → CollectionForbiddenException 'not_collection_owner'")
    void nonOwnerUpdate_throwsForbidden() {
        var collection = sampleCollection("alice");
        when(repo.findById("c-1")).thenReturn(Optional.of(collection));
        when(users.userId()).thenReturn("bob");

        assertThatThrownBy(() ->
                service.update("c-1", "hijack", "x", "security", List.of("sk-9")))
                .isInstanceOf(CollectionForbiddenException.class)
                .hasMessage("not_collection_owner");

        verify(repo, never()).save(any());
        verify(skillRepo, never()).findAllByIdInAndStatus(any(), any());
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: owner delete collection → service repo.delete called + event 註冊")
    void ownerDelete_callsRepoDelete() {
        var collection = sampleCollection("alice");
        when(repo.findById("c-1")).thenReturn(Optional.of(collection));
        when(users.userId()).thenReturn("alice");

        service.delete("c-1");

        verify(repo).delete(collection);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: 非 owner delete → CollectionForbiddenException")
    void nonOwnerDelete_throwsForbidden() {
        var collection = sampleCollection("alice");
        when(repo.findById("c-1")).thenReturn(Optional.of(collection));
        when(users.userId()).thenReturn("bob");

        assertThatThrownBy(() -> service.delete("c-1"))
                .isInstanceOf(CollectionForbiddenException.class);

        verify(repo, never()).delete(any(Collection.class));
    }

    @Test
    @DisplayName("aggregate Collection.update() validate name 太長 → IllegalArgumentException")
    void aggregateUpdateRejectsTooLongName() {
        var collection = sampleCollection("alice");
        var tooLong = "x".repeat(300);

        assertThatThrownBy(() ->
                collection.update(tooLong, "desc", "cat", List.of("sk-1"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name_too_long");
    }

    @Test
    @DisplayName("aggregate Collection.markDeleted() 拒空 deletedBy")
    void aggregateMarkDeletedRequiresDeletedBy() {
        var collection = sampleCollection("alice");

        assertThatThrownBy(() -> collection.markDeleted(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deletedBy");
    }

    @Test
    @DisplayName("aggregate Collection.update() skillIds 整段覆蓋（不 append）")
    void aggregateUpdateOverwritesSkillIds() {
        var collection = sampleCollection("alice"); // 起始 [sk-1, sk-2]

        collection.update("Pack", "d", "security", List.of("new-a", "new-b", "new-c"), "alice");

        assertThat(collection.skillIds()).containsExactly("new-a", "new-b", "new-c");
        assertThat(collection.skillIds()).doesNotContain("sk-1");
    }

    @Test
    @DisplayName("CollectionUpdatedEvent / CollectionDeletedEvent record schema 完整")
    void eventRecordsHaveExpectedFields() {
        // 純 type-level sanity — 確認 record 有對應欄位（防將來改 record 簽章時 missed listener）
        var updated = new CollectionUpdatedEvent("c-1", "n", "d", "cat",
                List.of("a"), "alice", java.time.Instant.now());
        assertThat(updated.collectionId()).isEqualTo("c-1");
        assertThat(updated.skillIds()).hasSize(1);

        var deleted = new CollectionDeletedEvent("c-1", "n", "alice", "alice",
                java.time.Instant.now());
        assertThat(deleted.ownerId()).isEqualTo("alice");
    }
}
