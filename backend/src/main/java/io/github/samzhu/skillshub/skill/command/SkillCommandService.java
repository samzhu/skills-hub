package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.shared.api.SkillValidationException;
import io.github.samzhu.skillshub.shared.api.ValidationFinding;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.skill.validation.ValidationResult;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * Application Service — orchestrate aggregate lifecycle + infrastructure（state-based path；ADR-002 Phase 2）。
 *
 * <p><b>S024 ship 後 final form</b>：每 command method 縮為 3-line orchestration（load + mutate + save）。
 * <ul>
 *   <li>Aggregate load via {@link SkillRepository#findById}（O(1) row read，取代 v1.5.0 ES O(events) replay）</li>
 *   <li>業務不變量 + state mutation 由 {@link Skill} 充血方法執行（{@code suspend} / {@code reactivate} /
 *       {@code recordVersionPublished}）。S167b 後 ACL grant/revoke 走 S114a {@code SkillGrantService}
 *       唯一寫入路徑（不再經 aggregate 充血方法）</li>
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
	private final NamedParameterJdbcTemplate jdbc;

	public SkillCommandService(SkillRepository skillRepo,
			SkillVersionRepository skillVersionRepo,
			StorageService storageService,
			PackageService packageService,
			SkillValidator skillValidator,
			NamedParameterJdbcTemplate jdbc) {
		this.skillRepo = skillRepo;
		this.skillVersionRepo = skillVersionRepo;
		this.storageService = storageService;
		this.packageService = packageService;
		this.skillValidator = skillValidator;
		this.jdbc = jdbc;
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

	/**
	 * S176 — upload canonical path：platform skillName 來自 request；SKILL.md name 保留在 version frontmatter。
	 *
	 * @param skillName platform-visible skill name（required；不同於 SKILL.md package name）
	 * @param authorNameSnapshot publish 時 freeze 的 author 顯示名稱（nullable；無 OIDC name claim 時 null）
	 */
	@Transactional
	public String uploadSkill(byte[] uploadedBytes, String skillName, String version, String author, String category,
			io.github.samzhu.skillshub.skill.domain.Visibility visibility,
			@org.jspecify.annotations.Nullable String authorNameSnapshot) throws IOException {
		// S053: normalize plain .md → 合法 zip；若已是 zip 原樣返回。下游流程一致 zip contract。
		var zipBytes = packageService.normalizeToZip(uploadedBytes);
		log.atInfo()
				.addKeyValue("skillName", skillName)
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
			var findings = buildFindings(validation);
			log.atWarn()
					.addKeyValue("author", author)
					.addKeyValue("findingsCount", findings.size())
					.log("SKILL.md 驗證失敗");
			throw new SkillValidationException("SKILL.md validation failed", findings);
		}

		var description = (String) validation.metadata().get("description");

		var skill = Skill.create(new CreateSkillCommand(
				skillName, description, author, category, visibility, authorNameSnapshot));
		skill.recordVersionPublished(version, authorNameSnapshot);
		var storagePath = "skills/" + skill.getId() + "/" + version + "/skill.zip";

		storageService.upload(storagePath, zipBytes);

		// 一次 save Skill — INSERT skills row + publish 兩 registered events（SkillCreatedEvent + SkillVersionPublishedFromAggregate）
		skillRepo.save(skill);

		// SkillVersion 獨立 aggregate INSERT + publish SkillVersionPublishedEvent（含 storagePath / fileSize / allowedTools 完整載荷）
		// S098a3-2: fileCount 從 packageService 計算給 PublishValidatePage upload-strip 顯實值用
		var fileCount = packageService.countEntries(zipBytes);
		var publishCmd = new PublishVersionCommand(
				skill.getId(), version, storagePath, zipBytes.length, fileCount, validation.metadata());
		skillVersionRepo.save(SkillVersion.publish(publishCmd));

		log.atInfo()
				.addKeyValue("skillId", skill.getId())
				.addKeyValue("skillName", skillName)
				.addKeyValue("packageName", validation.metadata().get("name"))
				.addKeyValue("version", version)
				.addKeyValue("storagePath", storagePath)
				.addKeyValue("fileCount", fileCount)
				.log("技能上傳完成，已發佈首版");
		return skill.getId();
	}

	/** S154 backward-compat 3-arg overload — null snapshot；既有 caller 不更新 author 顯示名稱。 */
	public void addVersion(String skillId, byte[] uploadedBytes, String version) throws IOException {
		addVersion(skillId, uploadedBytes, version, null);
	}

	/**
	 * S154 — 4-arg canonical：republish 時更新 {@link Skill#authorNameSnapshot}（per AC-5）。
	 *
	 * @param authorNameSnapshot 新版本的 author 顯示名稱（從 {@code currentUserProvider.current().name()}
	 *                           取得）；null 代表不更新 snapshot（保留既有 freeze 值）
	 */
	@Transactional
	public void addVersion(String skillId, byte[] uploadedBytes, String version,
			@org.jspecify.annotations.Nullable String authorNameSnapshot) throws IOException {
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
			var findings = buildFindings(validation);
			log.atWarn()
					.addKeyValue("skillId", skillId)
					.addKeyValue("findingsCount", findings.size())
					.log("SKILL.md 驗證失敗");
			throw new SkillValidationException("SKILL.md validation failed", findings);
		}

		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));

		// AC-7 service-layer predicate — friendly error before DB UNIQUE 兜底
		if (skillVersionRepo.existsBySkillIdAndVersion(skillId, version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		// S154: 透傳 snapshot；null → recordVersionPublished 內部不更新（既有 snapshot 保留）
		skill.recordVersionPublished(version, authorNameSnapshot);
		skillRepo.save(skill);

		var storagePath = "skills/" + skillId + "/" + version + "/skill.zip";
		storageService.upload(storagePath, zipBytes);

		// S098a3-2: fileCount 計算對齊 uploadSkill path
		var fileCount = packageService.countEntries(zipBytes);
		var publishCmd = new PublishVersionCommand(
				skillId, version, storagePath, zipBytes.length, fileCount, validation.metadata());
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

	/**
	 * S163 — owner 改 description / category。3-line orchestration（load → mutate → save）：
	 * aggregate 端 validate + registerEvent，service 不重複 logic。
	 */
	@Transactional
	public void updateSkill(String skillId, UpdateSkillCommand cmd, String updatedBy) {
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
		skill.update(cmd, updatedBy);
		skillRepo.save(skill);
		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("updatedBy", updatedBy)
				.log("Skill update 完成");
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

	/**
	 * S144 — hard-delete one skill and its user-facing references.
	 *
	 * <p>FK-backed tables are removed by PostgreSQL {@code ON DELETE CASCADE};
	 * this method only deletes soft-FK references that would otherwise become orphaned.
	 */
	@Transactional
	public void deleteSkill(String skillId, String deletedBy) {
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
		var storagePaths = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skillId).stream()
				.map(SkillVersion::getStoragePath)
				.toList();

		skill.markDeleted(deletedBy, storagePaths);
		deleteSoftReferences(skillId);
		skillRepo.delete(skill);

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("deletedBy", deletedBy)
				.addKeyValue("storagePathCount", storagePaths.size())
				.log("Skill 刪除完成");
	}

	private void deleteSoftReferences(String skillId) {
		var params = new MapSqlParameterSource("skillId", skillId);
		jdbc.update("DELETE FROM skill_subscriptions WHERE skill_id = :skillId", params);
		jdbc.update("DELETE FROM skill_scores WHERE skill_id = :skillId", params);
		jdbc.update("DELETE FROM collection_skills WHERE skill_id = :skillId", params);
		jdbc.update("DELETE FROM notifications WHERE skill_id = :skillId", params);
	}

	private List<ValidationFinding> buildFindings(ValidationResult r) {
		var list = new ArrayList<ValidationFinding>();
		for (var err : r.errors()) {
			list.add(new ValidationFinding("skill_md", "error", err, null));
		}
		for (var warn : r.warnings()) {
			list.add(new ValidationFinding("skill_md", "warning", warn, null));
		}
		return list;
	}
}
