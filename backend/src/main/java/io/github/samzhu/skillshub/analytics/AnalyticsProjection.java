package io.github.samzhu.skillshub.analytics;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;

/**
 * Analytics 模組的事件 Projection，監聽 {@link SkillDownloadedEvent} 並
 * 將每次下載行為寫入 {@code download_events} 表，供統計查詢使用。
 *
 * <p>此元件屬於 CQRS 讀取側，只消費事件、更新 Read Model，不產生新的領域事件。
 *
 * <p>S023：升級為 {@link ApplicationModuleListener}（async + AFTER_COMMIT +
 * REQUIRES_NEW + outbox 追蹤）。idempotency 透過
 * {@link DownloadEventRepository#saveIdempotent} 配合 V4
 * {@code download_events.event_id UNIQUE} constraint 達成 — 同一
 * {@code SkillDownloadedEvent.eventId} 重投不產生 duplicate row（per S023 spec §2.2）。
 */
@Component
class AnalyticsProjection {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final DownloadEventRepository repo;

	AnalyticsProjection(DownloadEventRepository repo) {
		this.repo = repo;
	}

	/**
	 * 處理 {@link SkillDownloadedEvent}，將下載記錄持久化至讀取模型（idempotent）。
	 *
	 * <p>用 {@link DownloadEventRepository#saveIdempotent} 而非 {@code save()} —
	 * 後者走 record + Persistable.isNew() 路徑，重投時產生 duplicate PK；
	 * 前者用 {@code ON CONFLICT (event_id) DO NOTHING} 嚴格冪等。
	 *
	 * @param event 由 skill 模組發布的 Skill 下載領域事件
	 */
	@ApplicationModuleListener
	void on(SkillDownloadedEvent event) {
		var rows = repo.saveIdempotent(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				event.version(),
				Instant.now(),
				event.eventId());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.addKeyValue("eventId", event.eventId())
				.addKeyValue("rowsAffected", rows)
				.log("收到 SkillDownloadedEvent，下載記錄已寫入（idempotent）");
	}

}
