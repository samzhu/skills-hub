package io.github.samzhu.skillshub.security;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Flag 讀取模型（Read Model），對應 Firestore 中的 {@code flags} Collection。
 * <p>
 * 此為 CQRS 查詢側資料，由 {@link FlagService#createFlag} 在接收到
 * {@code SkillFlagged} 領域事件後寫入，供查詢 API 直接回傳。
 * </p>
 *
 * @param id          Flag 唯一識別碼（UUID）
 * @param skillId     被舉報的 Skill 識別碼
 * @param type        舉報類型代碼
 * @param description 舉報原因描述
 * @param reportedBy  舉報人；MVP 階段固定為 {@code "anonymous"}
 * @param createdAt   舉報建立時間（UTC）
 * @param status      審核狀態，初始值為 {@code "OPEN"}
 */
@Document("flags")
// Firestore composite index required (create in Firestore Console):
//   collection: flags  fields: skillId ASC, createdAt DESC
// Used by: findBySkillIdOrderByCreatedAtDesc
// NOTE: auto-index-creation=false — Firestore 不支援透過 MongoDB wire protocol 建立 index。
@CompoundIndex(def = "{'skillId': 1, 'createdAt': -1}", name = "idx_skillId_createdAt")
public record FlagReadModel(
		@Id String id,
		String skillId,
		String type,
		String description,
		String reportedBy,
		Instant createdAt,
		String status
) {}
