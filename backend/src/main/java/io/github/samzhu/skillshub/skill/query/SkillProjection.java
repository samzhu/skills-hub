package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * 技能查詢側投影 — 消費領域事件，維護 {@code skills} 和 {@code skill_versions} 讀取模型。
 *
 * <p>採用同步 {@code @EventListener}（非 {@code @ApplicationModuleListener}），
 * 事件在 {@code publishEvent()} 時立即觸發，失敗會傳播回 command service（保證一致性）。</p>
 *
 * <p>處理的事件：</p>
 * <ul>
 *   <li>{@link SkillCreatedEvent} → 建立 {@link SkillReadModel}（初始狀態 DRAFT）</li>
 *   <li>{@link SkillVersionPublishedEvent} → 更新 latestVersion + 新增 {@link SkillVersionReadModel}</li>
 *   <li>{@link SkillDownloadedEvent} → 累加 downloadCount</li>
 * </ul>
 */
@Component
class SkillProjection {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final SkillReadModelRepository repo;
	private final SkillVersionReadModelRepository versionRepo;

	SkillProjection(SkillReadModelRepository repo, SkillVersionReadModelRepository versionRepo) {
		this.repo = repo;
		this.versionRepo = versionRepo;
	}

	/** 技能建立 → 初始化 read model，狀態設為 DRAFT，下載數為 0。 */
	@EventListener
	void on(SkillCreatedEvent event) {
		var now = Instant.now();
		var readModel = new SkillReadModel(
				event.aggregateId(),
				event.name(),
				event.description(),
				event.author(),
				event.category(),
				null,   // latestVersion: 尚未發佈任何版本
				null,   // riskLevel: 尚未完成風險評估
				"DRAFT",
				0L,
				now,
				now
		);
		repo.save(readModel);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("name", event.name())
				.log("投影已建立技能 read model");
	}

	/** 版本發佈 → 更新 skills 的 latestVersion + 新增 skill_versions 記錄。 */
	@EventListener
	void on(SkillVersionPublishedEvent event) {
		// 更新 skills read model 的最新版本
		repo.findById(event.aggregateId()).ifPresent(existing -> {
			var updated = new SkillReadModel(
					existing.id(),
					existing.name(),
					existing.description(),
					existing.author(),
					existing.category(),
					event.version(),
					existing.riskLevel(),
					existing.status(),
					existing.downloadCount(),
					existing.createdAt(),
					Instant.now()
			);
			repo.save(updated);
		});

		// 建立版本 read model 記錄
		var versionEntry = new SkillVersionReadModel(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				event.version(),
				event.storagePath(),
				event.fileSize(),
				event.frontmatter(),
				Instant.now()
		);
		versionRepo.save(versionEntry);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("投影已更新版本記錄");
	}

	/** 下載事件 → 累加 downloadCount。 */
	@EventListener
	void on(SkillDownloadedEvent event) {
		repo.findById(event.aggregateId()).ifPresent(existing -> {
			var updated = new SkillReadModel(
					existing.id(),
					existing.name(),
					existing.description(),
					existing.author(),
					existing.category(),
					existing.latestVersion(),
					existing.riskLevel(),
					existing.status(),
					existing.downloadCount() + 1,
					existing.createdAt(),
					Instant.now()
			);
			repo.save(updated);
		});

		log.atDebug()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("投影已累加下載次數");
	}

}
