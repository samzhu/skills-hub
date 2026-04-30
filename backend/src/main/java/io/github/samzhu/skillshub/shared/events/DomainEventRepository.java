package io.github.samzhu.skillshub.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * 領域事件倉儲介面（Spring Data JDBC）。
 *
 * <p>繼承 {@link ListCrudRepository} 提供基本 CRUD（save / findById / findAll / count
 * / deleteById），並額外定義事件溯源所需的查詢方法，以便依聚合根 ID 重放歷史事件
 * 或取得最新序號。
 *
 * <p>{@code (aggregate_id, sequence)} UNIQUE 約束（V1 schema
 * {@code idx_domain_events_aggregate_seq}）保證 Aggregate 樂觀鎖；違反時拋
 * {@link org.springframework.dao.DataIntegrityViolationException}。
 */
public interface DomainEventRepository extends ListCrudRepository<DomainEvent, String> {

	/**
	 * 依聚合根 ID 取得所有事件，按序號由小到大排序。
	 *
	 * <p>用於事件重放（replay），以正確順序還原聚合根狀態。
	 *
	 * @param aggregateId 聚合根識別碼
	 * @return 事件清單（序號升冪排列）
	 */
	List<DomainEvent> findByAggregateIdOrderBySequenceAsc(String aggregateId);

	/**
	 * 取得指定聚合根的最新一筆事件（序號最大）。
	 *
	 * <p>用於決定下一個事件的序號，實現樂觀鎖控制。
	 *
	 * @param aggregateId 聚合根識別碼
	 * @return 最新事件，若不存在則回傳 {@link Optional#empty()}
	 */
	Optional<DomainEvent> findTopByAggregateIdOrderBySequenceDesc(String aggregateId);

	/**
	 * S024 T05B — Audit log idempotent INSERT。
	 *
	 * <p><b>三層保險</b>：
	 * <ol>
	 *   <li>{@code pg_advisory_xact_lock(hashtext('audit:' || aggregate_id))} 序列化同 aggregate
	 *       的並發寫入 — 不同 aggregate 之間平行；同 aggregate 上多 listener 排隊，
	 *       避免 {@code MAX(sequence)} race 導致 {@code (aggregate_id, sequence)} UNIQUE 衝突</li>
	 *   <li>確定性 row id（呼叫端用 {@code UUID.nameUUIDFromBytes(dedupKey)} 產生）+
	 *       {@code ON CONFLICT (id) DO NOTHING} — 相同 dedupKey → 同 UUID，
	 *       Modulith retry 重投不產生 duplicate row</li>
	 *   <li>Sequence 在 INSERT 同條 SQL 內計算（{@code COALESCE(MAX, 0) + 1}），
	 *       配合 advisory lock 確保串行</li>
	 * </ol>
	 *
	 * <p>由 {@code AuditEventListener} 9 個 listener method 統一呼叫；取代 v1.5.0 ES path
	 * 的 {@code SkillCommandService.saveDomainEventOnly} transitional bridge。
	 *
	 * @param id            row 主鍵；由 {@code UUID.nameUUIDFromBytes(dedupKey.getBytes())} 確定性產生
	 * @param aggregateId   聚合根識別碼（如 {@code skills.id}）
	 * @param aggregateType 聚合根類型字串（如 {@code "Skill"}）
	 * @param eventType     事件類型字串（如 {@code "SkillCreated"}）
	 * @param payloadJson   事件 payload 已序列化為 JSON 字串（PostgreSQL CAST 為 jsonb）
	 * @param occurredAt    事件發生 UTC 時間戳
	 * @return 影響的 row 數（首次寫入回 1；重投導致 ON CONFLICT 跳過回 0）
	 */
	@Modifying
	@Query("""
			INSERT INTO domain_events (id, aggregate_id, aggregate_type, event_type, payload, sequence, occurred_at, metadata)
			SELECT :id, :aggregateId, :aggregateType, :eventType, CAST(:payloadJson AS jsonb),
			       COALESCE((SELECT MAX(sequence) FROM domain_events WHERE aggregate_id = :aggregateId), 0) + 1,
			       :occurredAt, '{}'::jsonb
			ON CONFLICT (id) DO NOTHING
			""")
	int saveAuditIdempotent(
			@Param("id") String id,
			@Param("aggregateId") String aggregateId,
			@Param("aggregateType") String aggregateType,
			@Param("eventType") String eventType,
			@Param("payloadJson") String payloadJson,
			@Param("occurredAt") Instant occurredAt);

}
