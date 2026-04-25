package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 技能讀取模型（CQRS 查詢側投影）— 對應 Firestore {@code skills} 集合的文件結構。
 *
 * <p>由查詢側事件監聽器消費 {@link io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent}、
 * {@link io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent} 及
 * {@link io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent} 後維護。
 * 此 record 為唯讀，不應從命令側直接寫入。
 *
 * @param id            技能唯一識別碼（與聚合根 aggregateId 相同）
 * @param name          技能名稱
 * @param description   技能功能描述
 * @param author        技能作者名稱
 * @param category      技能分類
 * @param latestVersion 最新發布的語意化版本號，尚未發布時為 {@code null}
 * @param riskLevel     安全評估結果（如 LOW、MEDIUM、HIGH），尚未評估時為 {@code null}
 * @param status        技能生命週期狀態（對應 {@link io.github.samzhu.skillshub.skill.domain.SkillStatus}）
 * @param downloadCount 累計下載次數
 * @param createdAt     技能建立時間戳
 * @param updatedAt     最後更新時間戳
 */
@Document("skills")
public record SkillReadModel(
		@Id String id,
		String name,
		String description,
		String author,
		String category,
		String latestVersion,
		String riskLevel,
		String status,
		long downloadCount,
		Instant createdAt,
		Instant updatedAt
) {}
