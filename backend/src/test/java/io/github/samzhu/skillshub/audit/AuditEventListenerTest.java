package io.github.samzhu.skillshub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import io.github.samzhu.skillshub.community.events.RequestCommentedEvent;
import io.github.samzhu.skillshub.community.events.RequestPostedEvent;
import io.github.samzhu.skillshub.community.events.RequestVotedEvent;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDeletedEvent;
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

        scenario.publish(new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "devops"))
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
        var event = new SkillCreatedEvent(skillId, "demo", "demo desc", "alice", "devops");

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

    @Test
    @DisplayName("AC-S144-4: SkillDeletedEvent → 1 SkillDeleted audit row；重投不疊加")
    @Tag("AC-S144-4")
    void skillDeleted_idempotentByAggregateId(Scenario scenario) {
        var skillId = newId();
        var event = new SkillDeletedEvent(skillId, "delete-service", "alice", Instant.now(),
                List.of("skills/%s/1.0.0.zip".formatted(skillId)));

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                })
                .andWaitForStateChange(() -> firstRowOrNull(skillId))
                .andVerify(row -> {
                    var rows = auditRowsFor(skillId);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().eventType()).isEqualTo("SkillDeleted");
                    assertThat(rows.getFirst().payload())
                            .containsEntry("name", "delete-service")
                            .containsEntry("deletedBy", "alice");
                });
    }

    // ─── S156c T03 — Request audit listeners (per spec AC-12 ES 永存) ────────────

    @Test
    @DisplayName("AC-12: RequestPostedEvent → 1 audit row (type=RequestPosted, aggregateType=Request)；重投不疊加")
    @Tag("AC-12")
    void requestPosted_writesAuditRow(Scenario scenario) {
        var requestId = newId();
        var event = new RequestPostedEvent(requestId, "需要 k8s autoscaler", "alice", Instant.now());

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event)); // dedup via requestId
                })
                .andWaitForStateChange(() -> firstRowOrNull(requestId))
                .andVerify(row -> {
                    var rows = auditRowsFor(requestId);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().eventType()).isEqualTo("RequestPosted");
                    assertThat(rows.getFirst().aggregateType()).isEqualTo("Request");
                    assertThat(rows.getFirst().payload())
                            .containsEntry("title", "需要 k8s autoscaler")
                            .containsEntry("requesterId", "alice");
                });
    }

    @Test
    @DisplayName("AC-12: 兩次 RequestVotedEvent（不同 eventId）→ 2 rows；重投同 eventId → 仍 1 row")
    @Tag("AC-12")
    void requestVoted_eachEventIdProducesNewRow(Scenario scenario) {
        var requestId = newId();
        var event1 = new RequestVotedEvent(UUID.randomUUID(), requestId, "alice", true, 1L, Instant.now());
        var event2 = new RequestVotedEvent(UUID.randomUUID(), requestId, "bob", true, 2L, Instant.now());

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event1));
                    tx.executeWithoutResult(s -> pub.publishEvent(event2));
                    tx.executeWithoutResult(s -> pub.publishEvent(event1)); // ON CONFLICT skip
                })
                .andWaitForStateChange(
                        () -> voteRowsFor(requestId),
                        rows -> rows.size() >= 2)
                .andVerify(rows -> assertThat(rows).hasSize(2));
    }

    @Test
    @DisplayName("AC-12: RequestCommentedEvent → 1 row（commentId dedupKey）；重投不疊加")
    @Tag("AC-12")
    void requestCommented_writesAuditRow(Scenario scenario) {
        var requestId = newId();
        var commentId = UUID.randomUUID().toString();
        var event = new RequestCommentedEvent(commentId, requestId, "bob", "+1 我也要", Instant.now());

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event)); // dedup via commentId
                })
                .andWaitForStateChange(() -> firstRowOrNull(requestId))
                .andVerify(row -> {
                    var rows = auditRowsFor(requestId).stream()
                            .filter(e -> "RequestCommented".equals(e.eventType()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().aggregateType()).isEqualTo("Request");
                    assertThat(rows.getFirst().payload())
                            .containsEntry("commentId", commentId)
                            .containsEntry("authorId", "bob")
                            .containsEntry("content", "+1 我也要");
                });
    }

    @Test
    @DisplayName("AC-12: Request 3 events 各觸發 1 次 → domain_events 3 rows + sequence 嚴格遞增")
    @Tag("AC-12")
    void requestThreeEvents_writeOrderedRows(Scenario scenario) {
        var requestId = newId();
        var commentId = UUID.randomUUID().toString();

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new RequestPostedEvent(requestId, "title", "alice", Instant.now())));
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new RequestVotedEvent(UUID.randomUUID(), requestId, "bob", true, 1L, Instant.now())));
                    tx.executeWithoutResult(s -> pub.publishEvent(
                            new RequestCommentedEvent(commentId, requestId, "bob", "+1", Instant.now())));
                })
                .andWaitForStateChange(
                        () -> auditRowsFor(requestId),
                        rows -> rows.size() == 3)
                .andVerify(rows -> {
                    assertThat(rows).extracting(DomainEvent::eventType)
                            .containsExactlyInAnyOrder("RequestPosted", "RequestVoted", "RequestCommented");
                    // sequence 嚴格遞增（per shared.events.DomainEventRepository.saveAuditIdempotent
                    // 的 MAX(sequence) + 1 邏輯 + advisory lock 序列化）
                    var seqs = rows.stream().map(DomainEvent::sequence).sorted().toList();
                    assertThat(seqs).isSorted();
                    assertThat(seqs.get(2) - seqs.get(0)).isGreaterThanOrEqualTo(2L);
                });
    }

    private List<DomainEvent> voteRowsFor(String aggregateId) {
        return auditRowsFor(aggregateId).stream()
                .filter(e -> "RequestVoted".equals(e.eventType()))
                .toList();
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
