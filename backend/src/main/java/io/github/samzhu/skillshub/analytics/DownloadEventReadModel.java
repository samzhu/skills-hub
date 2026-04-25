package io.github.samzhu.skillshub.analytics;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 下載事件讀取模型（Read Model），對應 Firestore／MongoDB 中的 {@code download_events} Collection。
 * <p>
 * 每筆記錄代表一次 Skill 套件下載行為，由 {@link AnalyticsProjection}
 * 在收到 {@code SkillDownloadedEvent} 後寫入，供統計查詢使用。
 * </p>
 *
 * @param id           下載事件唯一識別碼（UUID）
 * @param skillId      被下載的 Skill 識別碼
 * @param version      被下載的版本號
 * @param downloadedAt 下載發生的時間（UTC）
 */
@Document("download_events")
public record DownloadEventReadModel(
		@Id String id,
		String skillId,
		String version,
		Instant downloadedAt
) {}
