package io.github.samzhu.skillshub.security;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Flag 讀取模型（Read Model），對應 PostgreSQL {@code flags} 表。
 *
 * <p>此為 CQRS 查詢側資料，由 {@link FlagService#createFlag} 在接收到
 * {@code SkillFlagged} 領域事件後寫入，供查詢 API 直接回傳。
 *
 * <p>{@code idx_flags_skill} 索引對應既有 {@code findBySkillIdOrderByCreatedAtDesc}
 * derived query；S014 從 Firestore（MongoDB driver）遷至 PostgreSQL（Spring Data JDBC）。
 *
 * @param id          Flag 唯一識別碼（UUID 字串）
 * @param skillId     被舉報的 Skill 識別碼
 * @param type        舉報類型代碼
 * @param description 舉報原因描述
 * @param reportedBy  舉報人；MVP 階段固定為 {@code "anonymous"}
 * @param createdAt   舉報建立時間（UTC）
 * @param status      審核狀態，初始值為 {@code "OPEN"}
 */
@Table("flags")
public record FlagReadModel(
		@Id String id,
		@Column("skill_id") String skillId,
		@Column("type") String type,
		@Column("description") String description,
		@Column("reported_by") String reportedBy,
		@Column("created_at") Instant createdAt,
		@Column("status") String status
) implements Persistable<String> {

	/**
	 * 永遠回 true — Flag 為 append-only，由 FlagService 收 SkillFlagged 事件時建立。
	 *
	 * <p>S075：{@code @JsonIgnore} 隱藏此 framework hook，避免 API JSON 出現
	 * {@code "new": true} 干擾 client。完全平行於 Skill aggregate 的 S063 fix。
	 */
	@JsonIgnore
	@Override
	public boolean isNew() {
		return true;
	}

	@Override
	public String getId() {
		return id;
	}
}
