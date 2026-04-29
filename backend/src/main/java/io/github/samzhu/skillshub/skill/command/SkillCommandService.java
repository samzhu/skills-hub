package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * Application Service — 協調 infrastructure + aggregate + event store。
 *
 * <p>S024 T4 改寫為 state-based path（per ADR-002 Phase 2）：
 * <ul>
 *   <li>Aggregate load via {@link SkillRepository#findById}（O(1) row read，取代 ES O(events) replay）</li>
 *   <li>業務不變量 + state mutation 由 {@link Skill} 充血方法執行（{@code recordVersionPublished} /
 *       {@code recordSuspended} / {@code recordReactivated} / {@code recordAclGranted} / {@code recordAclRevoked}）</li>
 *   <li>Persist + publish 由 {@code skillRepo.save / skillVersionRepo.save} 透過 Spring Data
 *       {@code @DomainEvents} 自動觸發；events 進入 Modulith {@code event_publication} outbox 同 TX</li>
 *   <li>Version uniqueness 由 {@link SkillVersionRepository#existsBySkillIdAndVersion} 預檢
 *       （拋 {@link VersionExistsException} 友好錯誤；DB UNIQUE constraint 兜底）</li>
 * </ul>
 *
 * <p><b>T4 transitional bridge — 寫 domain_events table</b>：T4 僅做 service path 改寫；
 * domain_events audit log 仍由本 service {@link #saveDomainEventOnly} 直接寫入（保留既有 tests
 * 對 eventStore 的 assertion 不破）。T5 引入 AuditEventListener 訂閱 events 寫 audit row 後，
 * 本 transitional bridge（{@code eventStore.save / saveDomainEventOnly}）整組移除，
 * SkillCommandService 縮為 spec §2.7 設計的 3-行 orchestration（達成 AC-4 line-count 目標）。
 *
 * <p>業務規則封裝在 {@link Skill} Aggregate Root + {@link SkillVersion} 獨立 aggregate；
 * service 端僅 orchestrate（load → mutate → save）。
 */
@Service
public class SkillCommandService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final SkillRepository skillRepo;
	private final SkillVersionRepository skillVersionRepo;
	private final DomainEventRepository eventStore;
	private final StorageService storageService;
	private final PackageService packageService;
	private final SkillValidator skillValidator;

	public SkillCommandService(SkillRepository skillRepo,
			SkillVersionRepository skillVersionRepo,
			DomainEventRepository eventStore,
			StorageService storageService,
			PackageService packageService,
			SkillValidator skillValidator) {
		this.skillRepo = skillRepo;
		this.skillVersionRepo = skillVersionRepo;
		this.eventStore = eventStore;
		this.storageService = storageService;
		this.packageService = packageService;
		this.skillValidator = skillValidator;
	}

	@Transactional
	public String createSkill(CreateSkillCommand cmd) {
		var skill = Skill.create(cmd);
		skillRepo.save(skill);

		// T4 transitional: domain_events row（audit log；T5 由 AuditEventListener 接管後移除）
		saveDomainEventOnly(skill.getId(), "SkillCreated",
				Map.of("name", cmd.name(), "description", cmd.description(),
						"author", cmd.author(), "category", cmd.category()),
				1L);

		log.atInfo()
				.addKeyValue("skillId", skill.getId())
				.addKeyValue("name", cmd.name())
				.addKeyValue("author", cmd.author())
				.log("技能建立完成");
		return skill.getId();
	}

	@Transactional
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

		// new path — Skill aggregate + state mutation (recordVersionPublished triggers DRAFT→PUBLISHED)
		var skill = Skill.create(new CreateSkillCommand(name, description, author, category));
		skill.recordVersionPublished(version);
		var storagePath = "skills/" + skill.getId() + "/" + version + "/skill.zip";

		storageService.upload(storagePath, zipBytes);

		// 一次 save Skill — INSERT skills row + publish 兩 registered events（SkillCreatedEvent + SkillVersionPublishedFromAggregate）
		skillRepo.save(skill);

		// SkillVersion 獨立 aggregate INSERT + publish SkillVersionPublishedEvent
		var publishCmd = new PublishVersionCommand(
				skill.getId(), version, storagePath, zipBytes.length, validation.metadata());
		skillVersionRepo.save(SkillVersion.publish(publishCmd));

		// T4 transitional: 2 條 domain_events row（audit log）
		var allowedTools = parseAllowedTools(validation.metadata());
		saveDomainEventOnly(skill.getId(), "SkillCreated",
				Map.of("name", name, "description", description, "author", author, "category", category),
				1L);
		saveDomainEventOnly(skill.getId(), "SkillVersionPublished",
				Map.of("version", version, "storagePath", storagePath, "fileSize", (long) zipBytes.length,
						"allowedTools", allowedTools),
				2L);

		log.atInfo()
				.addKeyValue("skillId", skill.getId())
				.addKeyValue("name", name)
				.addKeyValue("version", version)
				.addKeyValue("storagePath", storagePath)
				.log("技能上傳完成，已發佈首版");
		return skill.getId();
	}

	@Transactional
	public void addVersion(String skillId, byte[] zipBytes, String version) throws IOException {
		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.addKeyValue("zipSize", zipBytes.length)
				.log("開始新增版本");

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
		var publishCmd = new PublishVersionCommand(
				skillId, version, storagePath, zipBytes.length, validation.metadata());

		// AC-7 service-layer predicate — friendly error before DB UNIQUE 兜底
		if (skillVersionRepo.existsBySkillIdAndVersion(skillId, version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
		skill.recordVersionPublished(version);
		skillRepo.save(skill);

		storageService.upload(storagePath, zipBytes);

		skillVersionRepo.save(SkillVersion.publish(publishCmd));

		// T4 transitional
		long nextSequence = nextEventSequence(skillId);
		saveDomainEventOnly(skillId, "SkillVersionPublished",
				Map.of("version", version, "storagePath", storagePath, "fileSize", (long) zipBytes.length),
				nextSequence);

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.log("版本新增完成");
	}

	@Transactional
	public void publishVersion(PublishVersionCommand cmd) {
		// AC-7 service-layer predicate
		if (skillVersionRepo.existsBySkillIdAndVersion(cmd.skillId(), cmd.version())) {
			throw new VersionExistsException("Version " + cmd.version() + " already exists");
		}
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordVersionPublished(cmd.version());
		skillRepo.save(skill);
		skillVersionRepo.save(SkillVersion.publish(cmd));

		// T4 transitional
		long nextSequence = nextEventSequence(cmd.skillId());
		saveDomainEventOnly(cmd.skillId(), "SkillVersionPublished",
				Map.of("version", cmd.version(), "storagePath", cmd.storagePath(),
						"fileSize", cmd.fileSize()),
				nextSequence);
	}

	@Transactional
	public void grantAcl(GrantAclCommand cmd) {
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordAclGranted(cmd);
		skillRepo.save(skill);

		long nextSequence = nextEventSequence(cmd.skillId());
		saveDomainEventOnly(cmd.skillId(), "SkillAclGranted",
				Map.of("type", cmd.type(), "principal", cmd.principal(),
						"permission", cmd.permission(), "grantedBy", cmd.grantedBy()),
				nextSequence);

		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("type", cmd.type())
				.addKeyValue("principal", cmd.principal())
				.addKeyValue("permission", cmd.permission())
				.addKeyValue("grantedBy", cmd.grantedBy())
				.log("ACL entry 授權完成");
	}

	@Transactional
	public void revokeAcl(RevokeAclCommand cmd) {
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordAclRevoked(cmd);
		skillRepo.save(skill);

		long nextSequence = nextEventSequence(cmd.skillId());
		saveDomainEventOnly(cmd.skillId(), "SkillAclRevoked",
				Map.of("type", cmd.type(), "principal", cmd.principal(),
						"permission", cmd.permission(), "revokedBy", cmd.revokedBy()),
				nextSequence);

		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("type", cmd.type())
				.addKeyValue("principal", cmd.principal())
				.addKeyValue("permission", cmd.permission())
				.addKeyValue("revokedBy", cmd.revokedBy())
				.log("ACL entry 撤銷完成");
	}

	@Transactional
	public void suspend(SuspendCommand cmd) {
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordSuspended(cmd);
		skillRepo.save(skill);

		long nextSequence = nextEventSequence(cmd.skillId());
		saveDomainEventOnly(cmd.skillId(), "SkillSuspended",
				Map.of("reason", cmd.reason(), "suspendedBy", cmd.suspendedBy()),
				nextSequence);

		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("reason", cmd.reason())
				.addKeyValue("suspendedBy", cmd.suspendedBy())
				.log("Skill 停用完成");
	}

	@Transactional
	public void reactivate(ReactivateCommand cmd) {
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordReactivated(cmd);
		skillRepo.save(skill);

		long nextSequence = nextEventSequence(cmd.skillId());
		saveDomainEventOnly(cmd.skillId(), "SkillReactivated",
				Map.of("reason", cmd.reason()),
				nextSequence);

		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("reason", cmd.reason())
				.log("Skill 重啟完成");
	}

	/**
	 * S018：從 frontmatter Map 解析 {@code allowed-tools} space-separated 字串為 List<String>。
	 * 與 {@link io.github.samzhu.skillshub.skill.domain.Skill} 同邏輯（保留 service 端透傳給
	 * domain_events payload；aggregate 端 SkillVersion.publish 也獨立解析存入 allowed_tools 欄位）。
	 */
	private static java.util.List<String> parseAllowedTools(Map<String, Object> frontmatter) {
		if (frontmatter == null) return java.util.List.of();
		var raw = frontmatter.get("allowed-tools");
		if (raw == null) return java.util.List.of();
		var asString = raw.toString().trim();
		if (asString.isEmpty()) return java.util.List.of();
		return java.util.List.of(asString.split("\\s+"));
	}

	/** Aggregate 樂觀鎖序列計算 — 從 domain_events 取最大 + 1。 */
	private long nextEventSequence(String aggregateId) {
		return eventStore.findTopByAggregateIdOrderBySequenceDesc(aggregateId)
				.map(e -> e.sequence() + 1).orElse(1L);
	}

	/**
	 * T4 transitional bridge — 寫 domain_events audit row（不 publish event；avoid 與
	 * skillRepo.save 的 @DomainEvents publish 重複觸發 listener）。
	 *
	 * <p>T5 引入 AuditEventListener 訂閱 events 寫 audit row 後，本 method 整組刪除，
	 * SkillCommandService 縮為 spec §2.7 的 3-行 orchestration（達成 AC-4）。
	 */
	private void saveDomainEventOnly(String aggregateId, String eventType, Map<String, Object> payload,
			long sequence) {
		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(), aggregateId, "Skill", eventType,
				payload, sequence, Instant.now(), Map.of());
		eventStore.save(domainEvent);
	}
}
