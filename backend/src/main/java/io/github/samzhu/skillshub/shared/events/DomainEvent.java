package io.github.samzhu.skillshub.shared.events;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 領域事件（Domain Event）資料結構。
 *
 * <p>事件溯源（Event Sourcing）的核心記錄單元，每筆記錄代表聚合根（Aggregate）
 * 在某一時間點所發生的業務事件，持久化至 Firestore 的 {@code domain_events} 集合。
 *
 * @param id            事件唯一識別碼（Firestore document ID，對應 {@code _id} 欄位）
 * @param aggregateId   所屬聚合根的識別碼
 * @param aggregateType 聚合根類型名稱（例如 {@code "Skill"}）
 * @param eventType     事件類型名稱（例如 {@code "SkillPublished"}）
 * @param payload       事件承載資料，以鍵值對形式儲存業務欄位
 * @param sequence      事件在同一聚合根下的順序號，用於重放與樂觀鎖
 * @param occurredAt    事件發生的 UTC 時間戳記
 * @param metadata      額外的上下文資訊（例如關聯 ID、操作人）
 */
@Document("domain_events")
// Firestore composite index required (create in Firestore Console):
//   collection: domain_events  fields: aggregateId ASC, sequence ASC
// Used by: findByAggregateIdOrderBySequenceAsc, findTopByAggregateIdOrderBySequenceDesc
// NOTE: auto-index-creation=false — Firestore 不支援透過 MongoDB wire protocol 建立 index。
@CompoundIndex(def = "{'aggregateId': 1, 'sequence': 1}", name = "idx_aggregateId_sequence")
public record DomainEvent(
		@Id String id,
		String aggregateId,
		String aggregateType,
		String eventType,
		Map<String, Object> payload,
		long sequence,
		Instant occurredAt,
		Map<String, String> metadata
) {}
