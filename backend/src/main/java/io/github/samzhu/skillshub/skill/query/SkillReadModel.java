package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 技能讀取模型（CQRS 查詢側投影）— 對應 PostgreSQL {@code skills} 表。
 *
 * <p>由查詢側事件監聽器消費 {@link io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent}、
 * {@link io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent} 及
 * {@link io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent} 後維護。
 * 此 record 為唯讀，不應從命令側直接寫入。
 *
 * <p>S014 從 Firestore（MongoDB driver）遷至 PostgreSQL（Spring Data JDBC）。
 *
 * @param id            技能唯一識別碼（與聚合根 aggregateId 相同）
 * @param name          技能名稱（unique constraint 由 schema 強制）
 * @param description   技能功能描述
 * @param author        技能作者名稱
 * @param category      技能分類
 * @param latestVersion 最新發布的語意化版本號，尚未發布時為 {@code null}
 * @param riskLevel     安全評估結果（如 LOW、MEDIUM、HIGH），尚未評估時為 {@code null}
 * @param status        技能生命週期狀態（對應 {@link io.github.samzhu.skillshub.skill.domain.SkillStatus}）
 * @param downloadCount 累計下載次數
 * @param createdAt     技能建立時間戳
 * @param updatedAt     最後更新時間戳
 * @param aclEntries    Row-level ACL flat string array（型如 {@code ["user:alice:read",
 *                      "role:admin:write", "group:eng:read"]}）— 由 S016 引入；空 list
 *                      代表「沒人可存取」（fail-secure 預設）。實際過濾走
 *                      {@code WHERE acl_entries ?| ARRAY[...]} + GIN(jsonb_ops) index。
 */
@Table("skills")
public record SkillReadModel(
		@Id String id,
		@Column("name") String name,
		@Column("description") String description,
		@Column("author") String author,
		@Column("category") String category,
		@Column("latest_version") String latestVersion,
		@Column("risk_level") String riskLevel,
		@Column("status") String status,
		@Column("download_count") long downloadCount,
		@Column("created_at") Instant createdAt,
		@Column("updated_at") Instant updatedAt,
		@Column("acl_entries") List<String> aclEntries
) implements Persistable<String> {

	/**
	 * Spring Data JDBC 用此方法判斷 INSERT 還是 UPDATE。
	 *
	 * <p>read-model 設計原則：projection 透過 {@code repo.save(...)} 只用於建立新 row（INSERT）；
	 * 既有 row 的更新都透過 {@link SkillReadModelRepository#incrementDownloadCount} /
	 * {@link SkillReadModelRepository#updateLatestVersion} /
	 * {@link SkillReadModelRepository#updateRiskLevel} 等 atomic @Modifying @Query。
	 * 因此 isNew() 永遠回 true，避免 Spring Data JDBC 對非 null id 預設「UPDATE」的誤判。
	 */
	@Override
	public boolean isNew() {
		return true;
	}

	@Override
	public String getId() {
		return id;
	}
}
