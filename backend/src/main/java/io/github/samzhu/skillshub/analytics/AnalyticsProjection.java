package io.github.samzhu.skillshub.analytics;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;

/**
 * Analytics 模組的事件 Projection，監聽 {@link SkillDownloadedEvent} 並
 * 將每次下載行為寫入 {@code download_events} Collection，供統計查詢使用。
 * <p>
 * 此元件屬於 CQRS 讀取側，只消費事件、更新 Read Model，不產生新的領域事件。
 * </p>
 */
@Component
class AnalyticsProjection {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final DownloadEventRepository repo;

	AnalyticsProjection(DownloadEventRepository repo) {
		this.repo = repo;
	}

	/**
	 * 處理 {@link SkillDownloadedEvent}，將下載記錄持久化至讀取模型。
	 *
	 * @param event 由 skill 模組發布的 Skill 下載領域事件
	 */
	@EventListener
	void on(SkillDownloadedEvent event) {
		var entry = new DownloadEventReadModel(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				event.version(),
				Instant.now()
		);
		repo.save(entry);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("收到 SkillDownloadedEvent，下載記錄已寫入");
	}

}
