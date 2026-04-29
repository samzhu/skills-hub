package io.github.samzhu.skillshub.analytics;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * {@link DownloadEventReadModel} 的 Spring Data JDBC Repository，提供下載事件
 * 的基本 CRUD 操作（對應 PostgreSQL {@code download_events} 表）。
 *
 * <p>S023 加入 {@link #saveIdempotent} — 配合 V4 {@code download_events.event_id UNIQUE}
 * constraint 提供 async listener retry 安全的寫入路徑。
 */
public interface DownloadEventRepository extends ListCrudRepository<DownloadEventReadModel, String> {

	/**
	 * Idempotent INSERT — {@code ON CONFLICT (event_id) DO NOTHING} 確保同一
	 * {@code eventId} 重投不產生 duplicate row（per S023 spec §2.2 + V4 migration）。
	 *
	 * <p>{@code metadata} 欄位省略 — 由 V1 schema 的 DB DEFAULT {@code '{}'::jsonb} 自動填入。
	 *
	 * @param id           下載事件唯一識別碼（UUID 字串）— 每次呼叫產生新值即可
	 * @param skillId      被下載的 Skill 識別碼
	 * @param version      被下載的版本號
	 * @param downloadedAt 下載時間（UTC）
	 * @param eventId      來源 {@code SkillDownloadedEvent.eventId}（idempotency key）
	 * @return 影響的 row 數（首次寫入回 1；conflict 跳過回 0）
	 */
	@Modifying
	@Query("""
			INSERT INTO download_events (id, skill_id, version, downloaded_at, event_id)
			VALUES (:id, :skillId, :version, :downloadedAt, :eventId)
			ON CONFLICT (event_id) DO NOTHING
			""")
	int saveIdempotent(@Param("id") String id,
			@Param("skillId") String skillId,
			@Param("version") String version,
			@Param("downloadedAt") Instant downloadedAt,
			@Param("eventId") String eventId);
}
