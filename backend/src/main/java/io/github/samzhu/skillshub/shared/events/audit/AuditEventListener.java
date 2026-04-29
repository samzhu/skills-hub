package io.github.samzhu.skillshub.shared.events.audit;

import java.lang.invoke.MethodHandles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

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
 * S024 T5 引入 — 訂閱所有 Skill 相關 domain events，未來作為 {@code domain_events}
 * audit log 的統一寫入點（取代 v1.5.0 ES path 的手動 {@code eventStore.save}）。
 *
 * <p><b>T5 scaffolding-only state — 目前僅 log，不寫 domain_events</b>：
 * 真正的 audit 寫入路徑與 v1.5.0 既有 sync {@code SkillCommandService.saveDomainEventOnly}
 * 並存會產生 {@code (aggregate_id, sequence)} UNIQUE 衝突或 duplicate audit row（per-aggregate
 * 序列計算 sync vs async 不一致）。idempotency 設計（如 ON CONFLICT DO NOTHING、event-id-based
 * dedup、或 sync→async 整體切換）需獨立 task 處理，per ADR-002 + S024 §6 task plan
 * deferred to T07（new task to be created）。
 *
 * <p><b>當前用途</b>：
 * <ul>
 *   <li>建立 listener scaffolding 與 module 邊界（{@code shared::events::audit}）— 為 T7
 *       完整 AuditEventListener 實作預留 wire 點</li>
 *   <li>觀測：每個 domain event 透過 INFO log 記錄 — 開發環境可從 log 查驗 event publish path</li>
 *   <li>{@code @ApplicationModuleListener} async + AFTER_COMMIT + outbox 追蹤 — Spring Modulith
 *       自動為每 method 註冊 {@code event_publication} row（per S023 outbox design），
 *       T1 cross-aggregate POC 驗證的 listener row count 將因本 listener 增加</li>
 * </ul>
 *
 * <p><b>未來實作（T7）</b>：
 * <ul>
 *   <li>每 listener 寫 domain_events row（aggregate_id / event_type / payload / sequence / occurredAt）</li>
 *   <li>Sequence 計算採 atomic SQL 或 ON CONFLICT 處理</li>
 *   <li>移除 {@code SkillCommandService.saveDomainEventOnly} transitional bridge</li>
 *   <li>修復 T4 期間 4 個 @Disabled tests（多數 race-related，async-only 路徑下行為更可預期）</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 * @see <a href="../../../../../../../../../docs/grimo/specs/2026-04-29-S024-skill-state-based-aggregate.md">S024 spec</a>
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @ApplicationModuleListener
    void on(SkillCreatedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillCreated")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("name", event.name())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillVersionPublishedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillVersionPublished")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("version", event.version())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillVersionPublishedFromAggregate event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillStateAdvancedToPublished")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("version", event.version())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillDownloadedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillDownloaded")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("version", event.version())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillAclGrantedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillAclGranted")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("principal", event.principal())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillAclRevokedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillAclRevoked")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("principal", event.principal())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillSuspendedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillSuspended")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("reason", event.reason())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillReactivatedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillReactivated")
                .addKeyValue("aggregateId", event.aggregateId())
                .addKeyValue("reason", event.reason())
                .log("[audit-stub] event observed");
    }

    @ApplicationModuleListener
    void on(SkillRiskAssessedEvent event) {
        log.atInfo()
                .addKeyValue("eventType", "SkillRiskAssessed")
                .addKeyValue("skillId", event.skillId())
                .addKeyValue("version", event.version())
                .addKeyValue("level", event.level())
                .log("[audit-stub] event observed");
    }
}
