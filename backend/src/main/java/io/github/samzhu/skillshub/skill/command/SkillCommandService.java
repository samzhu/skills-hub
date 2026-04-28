package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * Application Service — 協調 infrastructure + aggregate + event store。
 * 業務規則封裝在 {@link Skill} Aggregate Root。
 */
@Service
public class SkillCommandService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final DomainEventRepository eventStore;
	private final ApplicationEventPublisher events;
	private final StorageService storageService;
	private final PackageService packageService;
	private final SkillValidator skillValidator;

	public SkillCommandService(DomainEventRepository eventStore, ApplicationEventPublisher events,
			StorageService storageService, PackageService packageService, SkillValidator skillValidator) {
		this.eventStore = eventStore;
		this.events = events;
		this.storageService = storageService;
		this.packageService = packageService;
		this.skillValidator = skillValidator;
	}

	public String createSkill(CreateSkillCommand cmd) {
		var event = Skill.create(cmd.name(), cmd.description(), cmd.author(), cmd.category());
		saveAndPublish(event.aggregateId(), "SkillCreated",
				Map.of("name", cmd.name(), "description", cmd.description(),
						"author", cmd.author(), "category", cmd.category()),
				1L, event);
		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("name", cmd.name())
				.addKeyValue("author", cmd.author())
				.log("技能建立完成");
		return event.aggregateId();
	}

	public String uploadSkill(byte[] zipBytes, String version, String author, String category) throws IOException {
		log.atInfo()
				.addKeyValue("version", version)
				.addKeyValue("author", author)
				.addKeyValue("zipSize", zipBytes.length)
				.log("開始處理技能上傳");

		var skillMdContent = packageService.extractSkillMd(zipBytes);
		if (skillMdContent == null) {
			log.atWarn().addKeyValue("author", author).log("上傳的 zip 中找不到 SKILL.md");
			throw new IllegalArgumentException("SKILL.md not found in zip");
		}

		var validation = skillValidator.validate(skillMdContent);
		if (!validation.valid()) {
			log.atWarn()
					.addKeyValue("author", author)
					.addKeyValue("errors", validation.errors())
					.log("SKILL.md 驗證失敗");
			throw new IllegalArgumentException("SKILL.md validation failed: " + String.join("; ", validation.errors()));
		}

		var name = (String) validation.metadata().get("name");
		var description = (String) validation.metadata().get("description");

		var createdEvent = Skill.create(name, description, author, category);
		var aggregateId = createdEvent.aggregateId();
		var storagePath = "skills/" + aggregateId + "/" + version + "/skill.zip";

		storageService.upload(storagePath, zipBytes);

		saveAndPublish(aggregateId, "SkillCreated",
				Map.of("name", name, "description", description, "author", author, "category", category),
				1L, createdEvent);

		var versionEvent = new SkillVersionPublishedEvent(
				aggregateId, version, storagePath, zipBytes.length, validation.metadata());
		saveAndPublish(aggregateId, "SkillVersionPublished",
				Map.of("version", version, "storagePath", storagePath, "fileSize", (long) zipBytes.length),
				2L, versionEvent);

		log.atInfo()
				.addKeyValue("skillId", aggregateId)
				.addKeyValue("name", name)
				.addKeyValue("version", version)
				.addKeyValue("storagePath", storagePath)
				.log("技能上傳完成，已發佈首版");
		return aggregateId;
	}

	public void addVersion(String skillId, byte[] zipBytes, String version) throws IOException {
		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.addKeyValue("zipSize", zipBytes.length)
				.log("開始新增版本");

		var skill = loadAggregate(skillId);

		var skillMdContent = packageService.extractSkillMd(zipBytes);
		if (skillMdContent == null) {
			log.atWarn().addKeyValue("skillId", skillId).log("上傳的 zip 中找不到 SKILL.md");
			throw new IllegalArgumentException("SKILL.md not found in zip");
		}

		var validation = skillValidator.validate(skillMdContent);
		if (!validation.valid()) {
			log.atWarn()
					.addKeyValue("skillId", skillId)
					.addKeyValue("errors", validation.errors())
					.log("SKILL.md 驗證失敗");
			throw new IllegalArgumentException("SKILL.md validation failed: " + String.join("; ", validation.errors()));
		}

		var storagePath = "skills/" + skillId + "/" + version + "/skill.zip";

		var versionEvent = skill.publishVersion(version, storagePath, zipBytes.length, validation.metadata());

		storageService.upload(storagePath, zipBytes);

		saveAndPublish(skillId, "SkillVersionPublished",
				Map.of("version", version, "storagePath", storagePath, "fileSize", (long) zipBytes.length),
				skill.nextSequence(), versionEvent);

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.log("版本新增完成");
	}

	public void publishVersion(PublishVersionCommand cmd) {
		var skill = loadAggregate(cmd.skillId());
		var versionEvent = skill.publishVersion(cmd.version(), cmd.storagePath(), cmd.fileSize(), cmd.frontmatter());
		saveAndPublish(cmd.skillId(), "SkillVersionPublished",
				Map.of("version", cmd.version(), "storagePath", cmd.storagePath(), "fileSize", cmd.fileSize()),
				skill.nextSequence(), versionEvent);
	}

	/**
	 * S016：授權 ACL entry — 走 ES 路徑，aggregate 驗業務不變量後產生 SkillAclGranted。
	 */
	@Transactional
	public void grantAcl(GrantAclCommand cmd) {
		var skill = loadAggregate(cmd.skillId());
		var event = skill.grantAcl(cmd);
		saveAndPublish(cmd.skillId(), "SkillAclGranted",
				Map.of("type", cmd.type(), "principal", cmd.principal(),
						"permission", cmd.permission(), "grantedBy", cmd.grantedBy()),
				skill.nextSequence(), event);
		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("type", cmd.type())
				.addKeyValue("principal", cmd.principal())
				.addKeyValue("permission", cmd.permission())
				.addKeyValue("grantedBy", cmd.grantedBy())
				.log("ACL entry 授權完成");
	}

	/**
	 * S016：撤銷 ACL entry — 走 ES 路徑，aggregate 驗 entry 存在後產生 SkillAclRevoked。
	 */
	@Transactional
	public void revokeAcl(RevokeAclCommand cmd) {
		var skill = loadAggregate(cmd.skillId());
		var event = skill.revokeAcl(cmd);
		saveAndPublish(cmd.skillId(), "SkillAclRevoked",
				Map.of("type", cmd.type(), "principal", cmd.principal(),
						"permission", cmd.permission(), "revokedBy", cmd.revokedBy()),
				skill.nextSequence(), event);
		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("type", cmd.type())
				.addKeyValue("principal", cmd.principal())
				.addKeyValue("permission", cmd.permission())
				.addKeyValue("revokedBy", cmd.revokedBy())
				.log("ACL entry 撤銷完成");
	}

	private Skill loadAggregate(String skillId) {
		var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
		if (events.isEmpty()) {
			throw new IllegalArgumentException("Skill not found: " + skillId);
		}
		return new Skill(skillId, events);
	}

	private void saveAndPublish(String aggregateId, String eventType, Map<String, Object> payload,
			long sequence, Object applicationEvent) {
		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(), aggregateId, "Skill", eventType,
				payload, sequence, Instant.now(), Map.of());
		eventStore.save(domainEvent);
		events.publishEvent(applicationEvent);
	}

}
