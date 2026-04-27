package io.github.samzhu.skillshub.shared.events;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 領域事件（Domain Event）資料結構。
 *
 * <p>事件溯源（Event Sourcing）的核心記錄單元，每筆記錄代表聚合根（Aggregate）
 * 在某一時間點所發生的業務事件，持久化至 PostgreSQL {@code domain_events} 表。
 *
 * <p>S014 從 Firestore（MongoDB driver）遷至 PostgreSQL（Spring Data JDBC）。
 * {@code (aggregate_id, sequence)} UNIQUE 約束由 Flyway V1 schema 建立的
 * {@code idx_domain_events_aggregate_seq} index 強制執行，提供 Aggregate 樂觀鎖。
 *
 * <p>{@code payload} 與 {@code metadata} 透過
 * {@link io.github.samzhu.skillshub.shared.persistence.JdbcConfiguration} 註冊的
 * Map ↔ JSONB Converter 雙向序列化。
 *
 * @param id            事件唯一識別碼（UUID 字串）
 * @param aggregateId   所屬聚合根的識別碼
 * @param aggregateType 聚合根類型名稱（例如 {@code "Skill"}）
 * @param eventType     事件類型名稱（例如 {@code "SkillCreated"}）
 * @param payload       事件承載資料，以鍵值對形式儲存業務欄位（JSONB）
 * @param sequence      事件在同一聚合根下的順序號，用於重放與樂觀鎖
 * @param occurredAt    事件發生的 UTC 時間戳記
 * @param metadata      額外的上下文資訊（例如關聯 ID、操作人；型別 Map<String, Object> 配合 JSONB Converter）
 */
@Table("domain_events")
public record DomainEvent(
		@Id String id,
		@Column("aggregate_id") String aggregateId,
		@Column("aggregate_type") String aggregateType,
		@Column("event_type") String eventType,
		@Column("payload") Map<String, Object> payload,
		@Column("sequence") long sequence,
		@Column("occurred_at") Instant occurredAt,
		@Column("metadata") Map<String, Object> metadata
) implements Persistable<String> {

	/**
	 * Spring Data JDBC 用此方法判斷 INSERT 還是 UPDATE。
	 *
	 * <p>事件溯源語意：所有 DomainEvent 為**不可變**且**單向追加**，永遠 INSERT。
	 * 不實作 Persistable 時 Spring Data JDBC 預設用「id != null → UPDATE」，但我們的
	 * id 在應用端產生（UUID），永不為 null → save() 會誤判為 UPDATE → 0 rows affected。
	 */
	@Override
	public boolean isNew() {
		return true;
	}

	/** Persistable 要求的 getter；record 已 auto-generate {@code id()}，這裡 delegate。 */
	@Override
	public String getId() {
		return id;
	}
}
