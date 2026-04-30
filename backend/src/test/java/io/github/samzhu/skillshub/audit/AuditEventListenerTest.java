package io.github.samzhu.skillshub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.shared.events.TestEventTxHelper;
import io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRiskAssessedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedFromAggregate;

/**
 * S024 T05B AC-10 — AuditEventListener 真實 audit row 寫入 + idempotency 驗證。
 *
 * <p>對應 spec §3 AC-10 acceptance：每 listener 觸發一筆 domain_events row；重複觸發不疊加。
 * Async listener 用 Awaitility 等 Modulith 將 publication 分派至 thread pool 執行完成。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TestEventTxHelper txHelper;

    @Autowired
    private DomainEventRepository eventRepo;

    @Test
    @DisplayName("AC-10: SkillCreatedEvent → 1 audit row（type=SkillCreated, payload 含 name/desc/author/category）")
    @Tag("AC-10")
    void skillCreated_writesAuditRow() {
        var skillId = newId();
        txHelper.publishInTx(new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "DevOps"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var rows = auditRowsFor(skillId);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.eventType()).isEqualTo("SkillCreated");
            assertThat(row.payload()).containsEntry("name", "demo");
            assertThat(row.payload()).containsEntry("author", "alice");
        });
    }

    @Test
    @DisplayName("AC-10: 同 SkillCreatedEvent 重投 → 仍只 1 row（idempotent via deterministic id）")
    @Tag("AC-10")
    void skillCreated_idempotentOnRepublish() {
        var skillId = newId();
        var event = new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "DevOps");
        txHelper.publishInTx(event);
        txHelper.publishInTx(event);
        txHelper.publishInTx(event);

        await().pollDelay(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(auditRowsFor(skillId)).hasSize(1);
        });
    }

    @Test
    @DisplayName("AC-10: SkillVersionPublishedEvent → 1 audit row；同 sourceEventId 重投 → 不疊加")
    @Tag("AC-10")
    void versionPublished_idempotentBySourceEventId() {
        var skillId = newId();
        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0", "skills/x.zip", 100L,
                java.util.Map.of(), List.of("Bash"));

        txHelper.publishInTx(event);
        txHelper.publishInTx(event);

        await().pollDelay(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            var rows = auditRowsFor(skillId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().eventType()).isEqualTo("SkillVersionPublished");
        });
    }

    @Test
    @DisplayName("AC-10: SkillVersionPublishedFromAggregate → 1 row（aggregateId+version dedupKey）")
    @Tag("AC-10")
    void versionPublishedFromAggregate_writesAuditRow() {
        var skillId = newId();
        txHelper.publishInTx(new SkillVersionPublishedFromAggregate(skillId, "1.0.0"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var rows = auditRowsFor(skillId);
            assertThat(rows).extracting(DomainEvent::eventType)
                    .contains("SkillStateAdvancedToPublished");
        });
    }

    @Test
    @DisplayName("AC-10: 兩次 SkillDownloaded（不同 eventId）→ 2 rows；重投同 eventId → 仍 1 row")
    @Tag("AC-10")
    void downloaded_eachUniqueEventIdProducesNewRow() {
        var skillId = newId();
        var event1 = SkillDownloadedEvent.of(skillId, "1.0.0");
        var event2 = SkillDownloadedEvent.of(skillId, "1.0.0");

        txHelper.publishInTx(event1);
        txHelper.publishInTx(event2);
        txHelper.publishInTx(event1); // retry of event1 → ON CONFLICT skip

        await().pollDelay(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            var downloadRows = auditRowsFor(skillId).stream()
                    .filter(e -> "SkillDownloaded".equals(e.eventType()))
                    .toList();
            assertThat(downloadRows).hasSize(2);
        });
    }

    @Test
    @DisplayName("AC-10: SkillAclGranted/Revoked 各自 1 row；同內容重投不疊加")
    @Tag("AC-10")
    void aclEvents_writeAuditRows() {
        var skillId = newId();
        txHelper.publishInTx(new SkillAclGrantedEvent(skillId, "user", "bob", "read", "alice"));
        txHelper.publishInTx(new SkillAclRevokedEvent(skillId, "user", "bob", "read", "alice"));
        txHelper.publishInTx(new SkillAclGrantedEvent(skillId, "user", "bob", "read", "alice")); // dedup

        await().pollDelay(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            var types = auditRowsFor(skillId).stream().map(DomainEvent::eventType).toList();
            assertThat(types).contains("SkillAclGranted", "SkillAclRevoked");
            assertThat(types.stream().filter("SkillAclGranted"::equals).count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("AC-10: SkillSuspended / SkillReactivated 各自 1 row")
    @Tag("AC-10")
    void suspendReactivate_writeAuditRows() {
        var skillId = newId();
        txHelper.publishInTx(new SkillSuspendedEvent(skillId, "policy violation", "alice"));
        txHelper.publishInTx(new SkillReactivatedEvent(skillId, "review approved"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var types = auditRowsFor(skillId).stream().map(DomainEvent::eventType).toList();
            assertThat(types).contains("SkillSuspended", "SkillReactivated");
        });
    }

    @Test
    @DisplayName("AC-10: SkillRiskAssessed → 1 row（skillId+version+level dedupKey）")
    @Tag("AC-10")
    void riskAssessed_writesAuditRow() {
        var skillId = newId();
        txHelper.publishInTx(new SkillRiskAssessedEvent(skillId, "1.0.0", "HIGH", List.of()));
        txHelper.publishInTx(new SkillRiskAssessedEvent(skillId, "1.0.0", "HIGH", List.of())); // dedup

        await().pollDelay(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() -> {
            var riskRows = auditRowsFor(skillId).stream()
                    .filter(e -> "SkillRiskAssessed".equals(e.eventType()))
                    .toList();
            assertThat(riskRows).hasSize(1);
            assertThat(riskRows.getFirst().payload()).containsEntry("level", "HIGH");
        });
    }

    private List<DomainEvent> auditRowsFor(String aggregateId) {
        return eventRepo.findByAggregateIdOrderBySequenceAsc(aggregateId);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
