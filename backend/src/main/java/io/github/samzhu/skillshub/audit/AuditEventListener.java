package io.github.samzhu.skillshub.audit;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRiskAssessedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedFromAggregate;

/**
 * S024 T05B — 訂閱所有 Skill 相關 domain events，作為 {@code domain_events} audit log
 * 的統一寫入點。取代 v1.5.0 ES path 的 {@code SkillCommandService.saveDomainEventOnly}
 * transitional bridge（一同隨 T05B 移除）。
 *
 * <p><b>執行語意</b>：每 listener method 標 {@link ApplicationModuleListener}
 * （內含 {@code @Async + @TransactionalEventListener AFTER_COMMIT + REQUIRES_NEW}），
 * 由 Spring Modulith 在 SkillCommandService / SkillVersion aggregate save 後觸發；
 * 同 transaction 寫入 {@code event_publication} outbox row 提供至少一次（at-least-once）
 * 投遞保證 + retry 能力。
 *
 * <p><b>冪等性設計（idempotency）</b>：使用「dedupKey → 確定性 UUID」+
 * 「{@code ON CONFLICT (id) DO NOTHING}」雙層保險：
 * <ul>
 *   <li>每 event type 計算 dedupKey 字串（per-event 唯一識別；見各 method JavaDoc）</li>
 *   <li>{@code UUID.nameUUIDFromBytes(dedupKey.getBytes(UTF-8))} 確定性映射為同一 UUID</li>
 *   <li>{@link DomainEventRepository#saveAuditIdempotent} 用此 UUID 為 row id；
 *       Modulith retry 重投同 event → 同 UUID → DB 層 ON CONFLICT 跳過</li>
 * </ul>
 *
 * <p><b>Race 處理</b>：兩 listener 在同 aggregate 並發 → MAX(sequence) 計算可能 race →
 * 第二筆遇 {@code (aggregate_id, sequence)} UNIQUE 衝突 → Spring Modulith 自動 retry，
 * 第二輪讀 MAX 遞增 → 順利寫入。
 *
 * <p><b>7 個訂閱事件對應</b>（S167b 後；移除 SkillAclGrantedEvent / SkillAclRevokedEvent —
 * 改由 {@code SkillGrantService} → {@code skill_grants} 表 + {@code SkillAclProjectionListener}
 * 重建 {@code skills.acl_entries} 投影；新流程 audit log 追蹤待 follow-up spec）：
 * <table>
 *   <caption>event type ↔ event_type column ↔ dedupKey 構成</caption>
 *   <tr><th>Event class</th><th>event_type</th><th>dedupKey 構成</th></tr>
 *   <tr><td>SkillCreatedEvent</td><td>SkillCreated</td><td>aggregateId（一 skill 一筆）</td></tr>
 *   <tr><td>SkillVersionPublishedEvent</td><td>SkillVersionPublished</td><td>sourceEventId（事件自帶 UUID）</td></tr>
 *   <tr><td>SkillVersionPublishedFromAggregate</td><td>SkillStateAdvancedToPublished</td><td>aggregateId+version</td></tr>
 *   <tr><td>SkillDownloadedEvent</td><td>SkillDownloaded</td><td>eventId（事件自帶 UUID）</td></tr>
 *   <tr><td>SkillSuspendedEvent</td><td>SkillSuspended</td><td>aggregateId+suspendedBy+reason</td></tr>
 *   <tr><td>SkillReactivatedEvent</td><td>SkillReactivated</td><td>aggregateId+reason</td></tr>
 *   <tr><td>SkillRiskAssessedEvent</td><td>SkillRiskAssessed</td><td>skillId+version+level</td></tr>
 * </table>
 *
 * <p>業務不變量已在 aggregate 端阻止「同內容重複觸發」（state machine），故 content-based dedupKey
 * 對 retry 場景安全；唯一例外為 SkillDownloaded（用戶可重複下載），故依賴事件自帶的 {@code eventId}。
 *
 * @see DomainEventRepository#saveAuditIdempotent
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 * @see <a href="../../../../../../../../../docs/grimo/specs/2026-04-29-S024-skill-state-based-aggregate.md">S024 spec</a>
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String SKILL_AGGREGATE_TYPE = "Skill";

    private final DomainEventRepository eventRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditEventListener(DomainEventRepository eventRepo,
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.eventRepo = eventRepo;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @ApplicationModuleListener
    void on(SkillCreatedEvent event) {
        var payload = Map.<String, Object>of(
                "name", event.name(),
                "description", event.description(),
                "author", event.author() == null ? "" : event.author(),
                "category", event.category() == null ? "" : event.category());
        recordAudit(event.aggregateId(), "SkillCreated", payload,
                dedupKey("SkillCreated", event.aggregateId()));
    }

    @ApplicationModuleListener
    void on(SkillVersionPublishedEvent event) {
        var payload = Map.<String, Object>of(
                "version", event.version(),
                "storagePath", event.storagePath(),
                "fileSize", event.fileSize(),
                "allowedTools", event.allowedTools());
        recordAudit(event.aggregateId(), "SkillVersionPublished", payload,
                dedupKey("SkillVersionPublished", event.sourceEventId()));
    }

    @ApplicationModuleListener
    void on(SkillVersionPublishedFromAggregate event) {
        var payload = Map.<String, Object>of("version", event.version());
        recordAudit(event.aggregateId(), "SkillStateAdvancedToPublished", payload,
                dedupKey("SkillStateAdvancedToPublished", event.aggregateId(), event.version()));
    }

    @ApplicationModuleListener
    void on(SkillDownloadedEvent event) {
        var payload = Map.<String, Object>of("version", event.version());
        recordAudit(event.aggregateId(), "SkillDownloaded", payload,
                dedupKey("SkillDownloaded", event.eventId()));
    }

    @ApplicationModuleListener
    void on(SkillSuspendedEvent event) {
        var payload = Map.<String, Object>of(
                "reason", event.reason() == null ? "" : event.reason(),
                "suspendedBy", event.suspendedBy() == null ? "" : event.suspendedBy());
        recordAudit(event.aggregateId(), "SkillSuspended", payload,
                dedupKey("SkillSuspended", event.aggregateId(),
                        event.suspendedBy() == null ? "" : event.suspendedBy(),
                        event.reason() == null ? "" : event.reason()));
    }

    @ApplicationModuleListener
    void on(SkillReactivatedEvent event) {
        var payload = Map.<String, Object>of(
                "reason", event.reason() == null ? "" : event.reason());
        recordAudit(event.aggregateId(), "SkillReactivated", payload,
                dedupKey("SkillReactivated", event.aggregateId(),
                        event.reason() == null ? "" : event.reason()));
    }

    @ApplicationModuleListener
    void on(SkillRiskAssessedEvent event) {
        var payload = Map.<String, Object>of(
                "version", event.version(),
                "level", event.level());
        recordAudit(event.skillId(), "SkillRiskAssessed", payload,
                dedupKey("SkillRiskAssessed", event.skillId(), event.version(), event.level()));
    }

    /**
     * 統一 audit 寫入路徑。
     * <ol>
     *   <li>先以 {@code pg_advisory_xact_lock} 在獨立 SQL 取得 per-aggregate 序列化鎖
     *       — 同 aggregate 上其他 listener 在此排隊；不同 aggregate 平行；
     *       獨立 statement 關鍵原因：{@code MAX(sequence)} 子查詢需在 lock 取得後的新 statement
     *       snapshot 中計算（READ COMMITTED 下，後續 SQL 才看得到 lock holder 已 commit 的 row）</li>
     *   <li>序列化 payload + 確定性 row id + INSERT（{@link DomainEventRepository#saveAuditIdempotent}
     *       原子 SQL 計算下一 sequence + ON CONFLICT (id) DO NOTHING）</li>
     * </ol>
     */
    private void recordAudit(String aggregateId, String eventType,
            Map<String, Object> payload, String dedupKey) {
        try {
            // Step 1: per-aggregate advisory lock（同 TX 持有至 commit）
            jdbc.queryForList(
                    "SELECT pg_advisory_xact_lock(hashtext('audit:' || :aggregateId)::bigint)",
                    Collections.singletonMap("aggregateId", aggregateId));

            // Step 2: idempotent INSERT（fresh statement snapshot 看到 lock holder 已 commit 的 row → MAX 正確遞增）
            var rowId = deterministicId(dedupKey);
            var payloadJson = objectMapper.writeValueAsString(payload);
            int rows = eventRepo.saveAuditIdempotent(rowId, aggregateId, SKILL_AGGREGATE_TYPE,
                    eventType, payloadJson, Instant.now());
            log.atInfo()
                    .addKeyValue("eventType", eventType)
                    .addKeyValue("aggregateId", aggregateId)
                    .addKeyValue("rowsAffected", rows)
                    .log(rows == 1 ? "[audit] inserted" : "[audit] dedup-skip");
        } catch (RuntimeException e) {
            log.atError()
                    .setCause(e)
                    .addKeyValue("eventType", eventType)
                    .addKeyValue("aggregateId", aggregateId)
                    .log("[audit] write failed — Modulith 將自動 retry");
            // re-throw 讓 Modulith 將 publication 標 incomplete + 後續 retry
            throw e;
        }
    }

    /** dedupKey 字串建構 — 用 {@code |} 為 segment 分隔（避免 colon 與 ACL pattern 衝突）。 */
    private static String dedupKey(String... segments) {
        return String.join("|", segments);
    }

    /**
     * dedupKey → UUID v3（name-based）：相同字串永遠對應同一 UUID。
     * 配合 {@code ON CONFLICT (id) DO NOTHING} 提供 retry-safe 寫入。
     */
    private static String deterministicId(String dedupKey) {
        return UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
