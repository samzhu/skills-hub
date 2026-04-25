package io.github.samzhu.skillshub.skill.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.skill.command.VersionExistsException;

/**
 * Skill Aggregate Root — 封裝業務規則、產生 domain events。
 *
 * MVP 模式：不做 event replay 重建完整狀態，只追蹤已發佈版本號（用於重複版本檢查）。
 * 未來 ES-B1（Event Replay）可擴展為從 event stream 完整重建 aggregate 狀態。
 */
public class Skill {

	private final String aggregateId;
	private final Set<String> publishedVersions;
	private final long latestSequence;

	/**
	 * 從 event store 載入 aggregate（MVP: 只追蹤版本號 + 最新 sequence）
	 */
	public Skill(String aggregateId, List<DomainEvent> events) {
		this.aggregateId = aggregateId;
		this.publishedVersions = new HashSet<>();
		long maxSeq = 0;
		for (var event : events) {
			if ("SkillVersionPublished".equals(event.eventType())) {
				publishedVersions.add((String) event.payload().get("version"));
			}
			if (event.sequence() > maxSeq) {
				maxSeq = event.sequence();
			}
		}
		this.latestSequence = maxSeq;
	}

	/**
	 * Factory method — 建立新 skill，回傳 SkillCreated event
	 */
	public static SkillCreatedEvent create(String name, String description, String author, String category) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("description is required");
		}
		return new SkillCreatedEvent(UUID.randomUUID().toString(), name, description, author, category);
	}

	/**
	 * 發佈新版本 — 驗證版本不重複，回傳 SkillVersionPublished event
	 */
	public SkillVersionPublishedEvent publishVersion(String version, String storagePath,
			long fileSize, Map<String, Object> frontmatter) {
		if (publishedVersions.contains(version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		return new SkillVersionPublishedEvent(aggregateId, version, storagePath, fileSize, frontmatter);
	}

	public String aggregateId() {
		return aggregateId;
	}

	public long nextSequence() {
		return latestSequence + 1;
	}

}
