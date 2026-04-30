package io.github.samzhu.skillshub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
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
 * S024 T05B AC-10 / S025a-T01 — AuditEventListener audit row 寫入 + idempotency 驗證。
 *
 * <p>對應 spec §3 AC-10 acceptance：每 listener 觸發一筆 domain_events row；重複觸發不疊加。
 *
 * <p><b>S025a-T01 migration</b>：從 {@code @SpringBootTest + @TestEventTxHelper.publishInTx +
 * Awaitility 30s} 改為 {@code @ApplicationModuleTest(DIRECT_DEPENDENCIES) + Scenario}。
 * Scenario 內部走 Awaitility 但 timeout 由 {@code TestcontainersConfiguration.scenarioTimeout()}
 * 全域設為 5s（取代 S023-T07 30s band-aid）。多次 publish 同 event 為驗證 idempotency 場景，
 * 用 {@code scenario.stimulate((tx, pub) -> ...)} 控制每次 publish 為獨立 TX，模擬 outbox retry。
 *
 * <p><b>POC pilot</b>（S025a §6 POC Findings）：本 test 為 cross-module FK 最少干擾的 listener
 * （直接寫 {@code domain_events}，不依賴其他 listener output 結果）— 適合先驗 Scenario migration
 * pattern + 5s timeout baseline，為 T02 ScanOrchestrator 5s 可行性提供 latency 參考。
 *
 * @see io.github.samzhu.skillshub.TestcontainersConfiguration#scenarioTimeout
 * @see <a href="https://raw.githubusercontent.com/spring-projects/spring-modulith/2.0.6/spring-modulith-test/src/main/java/org/springframework/modulith/test/Scenario.java">Spring Modulith Scenario API</a>
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class AuditEventListenerTest {

    @Autowired
    private DomainEventRepository eventRepo;

    @Test
    @DisplayName("AC-10: SkillCreatedEvent → 1 audit row（type=SkillCreated, payload 含 name/desc/author/category）")
    @Tag("AC-10")
    void skillCreated_writesAuditRow(Scenario scenario) {
        var skillId = newId();

        scenario.publish(new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "DevOps"))
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> {
                    assertThat(row.eventType()).isEqualTo("SkillCreated");
                    assertThat(row.payload()).containsEntry("name", "demo");
                    assertThat(row.payload()).containsEntry("author", "alice");
                });
    }

    @Test
    @DisplayName("AC-10: 同 SkillCreatedEvent 重投 → 仍只 1 row（idempotent via deterministic id）")
    @Tag("AC-10")
    void skillCreated_idempotentOnRepublish(Scenario scenario) {
        var skillId = newId();
        var event = new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "DevOps");

        // 三次獨立 TX publish，模擬 outbox retry；audit listener 透過 deterministic UUID
        // (UUID.nameUUIDFromBytes(dedupKey)) + ON CONFLICT DO NOTHING 確保只寫 1 row。
        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                })
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> assertThat(auditRowsFor(skillId)).hasSize(1));
    }

    @Test
    @DisplayName("AC-10: SkillVersionPublishedEvent → 1 audit row；同 sourceEventId 重投 → 不疊加")
    @Tag("AC-10")
    void versionPublished_idempotentBySourceEventId(Scenario scenario) {
        var skillId = newId();
        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0", "skills/x.zip", 100L,
                java.util.Map.of(), List.of("Bash"));

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                })
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> {
                    var rows = auditRowsFor(skillId);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().eventType()).isEqualTo("SkillVersionPublished");
                });
    }

    @Test
    @DisplayName("AC-10: SkillVersionPublishedFromAggregate → 1 row（aggregateId+version dedupKey）")
    @Tag("AC-10")
    void versionPublishedFromAggregate_writesAuditRow(Scenario scenario) {
        var skillId = newId();

        scenario.publish(new SkillVersionPublishedFromAggregate(skillId, "1.0.0"))
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> assertThat(auditRowsFor(skillId))
                        .extracting(DomainEvent::eventType)
                        .contains("SkillStateAdvancedToPublished"));
    }

    @Test
    @DisplayName("AC-10: 兩次 SkillDownloaded（不同 eventId）→ 2 rows；重投同 eventId → 仍 1 row")
    @Tag("AC-10")
    void downloaded_eachUniqueEventIdProducesNewRow(Scenario scenario) {
        var skillId = newId();
        var event1 = SkillDownloadedEvent.of(skillId, "1.0.0");
        var event2 = SkillDownloadedEvent.of(skillId, "1.0.0");

        // event1 twice + event2 once：dedup 走 eventId，event1 不疊加，最終 2 rows。
        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event1));
                    tx.executeWithoutResult(s -> pub.publishEvent(event2));
                    tx.executeWithoutResult(s -> pub.publishEvent(event1)); // ON CONFLICT skip
                })
                .andWaitForStateChange(
                        () -> downloadRowsFor(skillId),
                        rows -> rows.size() >= 2)
                .andVerify(rows -> assertThat(rows).hasSize(2));
    }

    @Test
    @DisplayName("AC-10: SkillAclGranted/Revoked 各自 1 row；同內容重投不疊加")
    @Tag("AC-10")
    void aclEvents_writeAuditRows(Scenario scenario) {
        var skillId = newId();

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new SkillAclGrantedEvent(skillId, "user", "bob", "read", "alice")));
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new SkillAclRevokedEvent(skillId, "user", "bob", "read", "alice")));
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new SkillAclGrantedEvent(skillId, "user", "bob", "read", "alice"))); // dedup
                })
                .andWaitForStateChange(
                        () -> auditRowsFor(skillId),
                        rows -> rows.stream().map(DomainEvent::eventType).toList()
                                .containsAll(List.of("SkillAclGranted", "SkillAclRevoked")))
                .andVerify(rows -> {
                    var types = rows.stream().map(DomainEvent::eventType).toList();
                    assertThat(types).contains("SkillAclGranted", "SkillAclRevoked");
                    assertThat(types.stream().filter("SkillAclGranted"::equals).count()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("AC-10: SkillSuspended / SkillReactivated 各自 1 row")
    @Tag("AC-10")
    void suspendReactivate_writeAuditRows(Scenario scenario) {
        var skillId = newId();

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new SkillSuspendedEvent(skillId, "policy violation", "alice")));
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new SkillReactivatedEvent(skillId, "review approved")));
                })
                .andWaitForStateChange(
                        () -> auditRowsFor(skillId),
                        rows -> rows.stream().map(DomainEvent::eventType).toList()
                                .containsAll(List.of("SkillSuspended", "SkillReactivated")))
                .andVerify(rows -> assertThat(rows).extracting(DomainEvent::eventType)
                        .contains("SkillSuspended", "SkillReactivated"));
    }

    @Test
    @DisplayName("AC-10: SkillRiskAssessed → 1 row（skillId+version+level dedupKey）")
    @Tag("AC-10")
    void riskAssessed_writesAuditRow(Scenario scenario) {
        var skillId = newId();
        var event = new SkillRiskAssessedEvent(skillId, "1.0.0", "HIGH", List.of());

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event)); // dedup
                })
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> {
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

    private List<DomainEvent> downloadRowsFor(String aggregateId) {
        return auditRowsFor(aggregateId).stream()
                .filter(e -> "SkillDownloaded".equals(e.eventType()))
                .toList();
    }

    /**
     * Scenario.andWaitForStateChange 的 supplier 在「非 null / 非 empty / true」時退出 wait；
     * 此 helper 提供 first row（非 null 即可），詳細斷言交給 .andVerify。
     */
    private DomainEvent firstRowOrNull(String aggregateId) {
        var rows = auditRowsFor(aggregateId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
