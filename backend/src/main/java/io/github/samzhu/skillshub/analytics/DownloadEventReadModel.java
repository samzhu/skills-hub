package io.github.samzhu.skillshub.analytics;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 下載事件讀取模型（Read Model），對應 PostgreSQL {@code download_events} 表。
 *
 * <p>每筆記錄代表一次 Skill 套件下載行為，由 {@link AnalyticsProjection}
 * 在收到 {@code SkillDownloadedEvent} 後寫入，供統計查詢使用。
 *
 * <p>{@code idx_download_events_skill_time} 索引（skill_id, downloaded_at DESC）
 * 加速分析查詢。S014 從 Firestore（MongoDB driver）遷至 PostgreSQL（Spring Data JDBC）。
 *
 * <p>S023 加入 {@code eventId} 欄位 — 對應 V4 migration 新增的
 * {@code download_events.event_id VARCHAR(36) NOT NULL} 與 UNIQUE index
 * {@code uq_download_events_event_id}。{@link DownloadEventRepository#saveIdempotent}
 * 透過 {@code ON CONFLICT (event_id) DO NOTHING} 確保 async listener retry 場景下
 * 不重複寫入。
 *
 * <p>Schema 含 {@code metadata JSONB DEFAULT '{}'::jsonb} 欄位（為未來匿名追蹤
 * metadata 預留；本 record 暫不映射，DB 端 DEFAULT 自動處理）。
 *
 * @param id           下載事件唯一識別碼（UUID 字串）
 * @param skillId      被下載的 Skill 識別碼
 * @param version      被下載的版本號
 * @param downloadedAt 下載發生的時間（UTC）
 * @param eventId      來源 {@code SkillDownloadedEvent.eventId}（idempotency key；S023）
 */
@Table("download_events")
public record DownloadEventReadModel(
		@Id String id,
		@Column("skill_id") String skillId,
		@Column("version") String version,
		@Column("downloaded_at") Instant downloadedAt,
		@Column("event_id") String eventId
) implements Persistable<String> {

	/** 永遠回 true — DownloadEvent 為 append-only。 */
	@Override
	public boolean isNew() {
		return true;
	}

	@Override
	public String getId() {
		return id;
	}
}
