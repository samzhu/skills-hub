package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * Application Service — orchestrate aggregate lifecycle + infrastructure（state-based path；ADR-002 Phase 2）。
 *
 * <p><b>S024 ship 後 final form</b>：每 command method 縮為 3-line orchestration（load + mutate + save）。
 * <ul>
 *   <li>Aggregate load via {@link SkillRepository#findById}（O(1) row read，取代 v1.5.0 ES O(events) replay）</li>
 *   <li>業務不變量 + state mutation 由 {@link Skill} 充血方法執行（{@code suspend} / {@code reactivate} /
 *       {@code grantAcl} / {@code revokeAcl} / {@code recordVersionPublished}）</li>
 *   <li>Persist + publish 由 {@code skillRepo.save / skillVersionRepo.save} 透過 Spring Data
 *       {@code @DomainEvents} 自動觸發；events 進入 Modulith {@code event_publication} outbox 同 TX</li>
 *   <li>Audit log（domain_events table 寫入）由 {@code AuditEventListener} 訂閱 events 後 async 處理 —
 *       本 service 不再直接寫 domain_events row</li>
 *   <li>Version uniqueness 由 {@link SkillVersionRepository#existsBySkillIdAndVersion} 預檢
 *       （拋 {@link VersionExistsException} 友好錯誤；DB UNIQUE constraint 兜底）</li>
 * </ul>
 */
@Service
public class SkillCommandService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final SkillRepository skillRepo;
	private final SkillVersionRepository skillVersionRepo;
	private final StorageService storageService;
	private final PackageService packageService;
	private final SkillValidator skillValidator;

	public SkillCommandService(SkillRepository skillRepo,
			SkillVersionRepository skillVersionRepo,
			StorageService storageService,
			PackageService packageService,
			SkillValidator skillValidator) {
		this.skillRepo = skillRepo;
		this.skillVersionRepo = skillVersionRepo;
		this.storageService = storageService;
		this.packageService = packageService;
		this.skillValidator = skillValidator;
	}

	@Transactional
	public String createSkill(CreateSkillCommand cmd) {
		var skill = Skill.create(cmd);
		skillRepo.save(skill);
		log.atInfo()
				.addKeyValue("skillId", skill.getId())
				.addKeyValue("name", cmd.name())
				.addKeyValue("author", cmd.author())
				.log("技能建立完成");
		return skill.getId();
	}

	@Transactional
	public String uploadSkill(byte[] uploadedBytes, String version, String author, String category) throws IOException {
		// S053: normalize plain .md → 合法 zip；若已是 zip 原樣返回。下游流程一致 zip contract。
		var zipBytes = packageService.normalizeToZip(uploadedBytes);
		log.atInfo()
				.addKeyValue("version", version)
				.addKeyValue("author", author)
				.addKeyValue("uploadedSize", uploadedBytes.length)
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

		var skill = Skill.create(new CreateSkillCommand(name, description, author, category));
		skill.recordVersionPublished(version);
		var storagePath = "skills/" + skill.getId() + "/" + version + "/skill.zip";

		storageService.upload(storagePath, zipBytes);

		// 一次 save Skill — INSERT skills row + publish 兩 registered events（SkillCreatedEvent + SkillVersionPublishedFromAggregate）
		skillRepo.save(skill);

		// SkillVersion 獨立 aggregate INSERT + publish SkillVersionPublishedEvent（含 storagePath / fileSize / allowedTools 完整載荷）
		var publishCmd = new PublishVersionCommand(
				skill.getId(), version, storagePath, zipBytes.length, validation.metadata());
		skillVersionRepo.save(SkillVersion.publish(publishCmd));

		log.atInfo()
				.addKeyValue("skillId", skill.getId())
				.addKeyValue("name", name)
				.addKeyValue("version", version)
				.addKeyValue("storagePath", storagePath)
				.log("技能上傳完成，已發佈首版");
		return skill.getId();
	}

	@Transactional
	public void addVersion(String skillId, byte[] uploadedBytes, String version) throws IOException {
		// S053: normalize plain .md / subfolder zip → 標準化 zip（SKILL.md 在根）
		var zipBytes = packageService.normalizeToZip(uploadedBytes);
		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.addKeyValue("uploadedSize", uploadedBytes.length)
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

		// S032: 防 download zip metadata 與平台 listing name 不一致 — 早於 version 重複檢查上移 findById
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
		var zipName = (String) validation.metadata().get("name");
		if (zipName != null && !zipName.equals(skill.getName())) {
			log.atWarn()
					.addKeyValue("skillId", skillId)
					.addKeyValue("aggregateName", skill.getName())
					.addKeyValue("zipName", zipName)
					.log("SKILL.md name 與 skill aggregate 不一致");
			throw new IllegalArgumentException(
					"SKILL.md name '" + zipName + "' does not match skill name '" + skill.getName() + "'");
		}

		// AC-7 service-layer predicate — friendly error before DB UNIQUE 兜底
		if (skillVersionRepo.existsBySkillIdAndVersion(skillId, version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		skill.recordVersionPublished(version);
		skillRepo.save(skill);

		var storagePath = "skills/" + skillId + "/" + version + "/skill.zip";
		storageService.upload(storagePath, zipBytes);

		var publishCmd = new PublishVersionCommand(
				skillId, version, storagePath, zipBytes.length, validation.metadata());
		skillVersionRepo.save(SkillVersion.publish(publishCmd));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version)
				.log("版本新增完成");
	}

	@Transactional
	public void publishVersion(PublishVersionCommand cmd) {
		if (skillVersionRepo.existsBySkillIdAndVersion(cmd.skillId(), cmd.version())) {
			throw new VersionExistsException("Version " + cmd.version() + " already exists");
		}
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.recordVersionPublished(cmd.version());
		skillRepo.save(skill);
		skillVersionRepo.save(SkillVersion.publish(cmd));
	}

	@Transactional
	public void grantAcl(GrantAclCommand cmd) {
		var skill = skillRepo.findById(cmd.skillId())
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + cmd.skillId()));
		skill.grantAcl(cmd);
		skillRepo.save(skill);
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
		skill.revokeAcl(cmd);
		skillRepo.save(skill);
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
		skill.suspend(cmd);
		skillRepo.save(skill);
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
		skill.reactivate(cmd);
		skillRepo.save(skill);
		log.atInfo()
				.addKeyValue("skillId", cmd.skillId())
				.addKeyValue("reason", cmd.reason())
				.log("Skill 重啟完成");
	}
}
