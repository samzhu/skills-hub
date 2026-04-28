package io.github.samzhu.skillshub.skill.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;
import io.github.samzhu.skillshub.skill.command.VersionExistsException;

/**
 * Skill Aggregate Root — 封裝業務規則、產生 domain events。
 *
 * <p>狀態 replay（minimal）：
 * <ul>
 *   <li>{@code publishedVersions} — S014 加；驗版本不重複（{@link VersionExistsException}）</li>
 *   <li>{@code currentAclEntries} — S016 加；驗 grant/revoke 不變量（無重複 grant、revoke 必須存在）</li>
 *   <li>{@code status} — S018 加；state machine 不變量集中於 {@link SkillStatus}（DRAFT → PUBLISHED → SUSPENDED → PUBLISHED）</li>
 * </ul>
 *
 * <p>未來 ES-B1（Event Replay）可擴展為完整重建。
 */
public class Skill {

	private final String aggregateId;
	private final Set<String> publishedVersions;
	private final Set<String> currentAclEntries;
	private SkillStatus status;
	private final long latestSequence;

	/**
	 * 從 event store 載入 aggregate — replay 累積版本號 + ACL entries + status + 最新 sequence。
	 */
	public Skill(String aggregateId, List<DomainEvent> events) {
		this.aggregateId = aggregateId;
		this.publishedVersions = new HashSet<>();
		this.currentAclEntries = new HashSet<>();
		this.status = SkillStatus.DRAFT;   // SkillCreated 之後預設 DRAFT；後續事件 transition
		long maxSeq = 0;
		for (var event : events) {
			switch (event.eventType()) {
				case "SkillCreated" ->
					this.status = SkillStatus.DRAFT;
				case "SkillVersionPublished" -> {
					publishedVersions.add((String) event.payload().get("version"));
					// first version → DRAFT.publish() = PUBLISHED；後續 idempotent
					this.status = this.status.publish();
				}
				case "SkillSuspended" ->
					this.status = this.status.suspend();
				case "SkillReactivated" ->
					this.status = this.status.reactivate();
				case "SkillAclGranted" ->
					currentAclEntries.add(formatEntry(event.payload()));
				case "SkillAclRevoked" ->
					currentAclEntries.remove(formatEntry(event.payload()));
				default -> {
					// 其他事件類型於本 aggregate 不需要 replay state（只取 maxSeq）
				}
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
	 * 發佈新版本 — 驗證 status 不變量（不能在 SUSPENDED 發版）+ 版本不重複，回傳 SkillVersionPublished event。
	 *
	 * <p>S018：state machine 預檢查先於版本不變量；SUSPENDED skill 連版本檢查都不執行。
	 */
	public SkillVersionPublishedEvent publishVersion(String version, String storagePath,
			long fileSize, Map<String, Object> frontmatter) {
		// state machine guard — SUSPENDED 不允許 publish；DRAFT/PUBLISHED 都允許
		this.status.publish();
		if (publishedVersions.contains(version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		return new SkillVersionPublishedEvent(aggregateId, version, storagePath, fileSize, frontmatter,
				parseAllowedTools(frontmatter));
	}

	/**
	 * S018：從 frontmatter `allowed-tools` space-separated 字串解析為 List<String>。
	 * key 不存在或 null → empty list（fail-secure；既有 events replay 不破）。
	 */
	private static List<String> parseAllowedTools(Map<String, Object> frontmatter) {
		if (frontmatter == null) return List.of();
		var raw = frontmatter.get("allowed-tools");
		if (raw == null) return List.of();
		var asString = raw.toString().trim();
		if (asString.isEmpty()) return List.of();
		return List.of(asString.split("\\s+"));
	}

	/**
	 * S018：停用 skill — state machine 驗 PUBLISHED → SUSPENDED 唯一合法 transition。
	 */
	public SkillSuspendedEvent suspend(SuspendCommand cmd) {
		this.status.suspend();
		return new SkillSuspendedEvent(aggregateId, cmd.reason(), cmd.suspendedBy());
	}

	/**
	 * S018：重啟 skill — state machine 驗 SUSPENDED → PUBLISHED 唯一合法 transition。
	 */
	public SkillReactivatedEvent reactivate(ReactivateCommand cmd) {
		this.status.reactivate();
		return new SkillReactivatedEvent(aggregateId, cmd.reason());
	}

	/**
	 * 授權 ACL entry — 業務不變量：相同 type:principal:permission 不可重複 grant。
	 */
	public SkillAclGrantedEvent grantAcl(GrantAclCommand cmd) {
		var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
		if (currentAclEntries.contains(entry)) {
			throw new IllegalStateException("ACL entry already exists: " + entry);
		}
		return new SkillAclGrantedEvent(
				aggregateId, cmd.type(), cmd.principal(), cmd.permission(), cmd.grantedBy());
	}

	/**
	 * 撤銷 ACL entry — 業務不變量：必須存在對應 entry 才可 revoke。
	 */
	public SkillAclRevokedEvent revokeAcl(RevokeAclCommand cmd) {
		var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
		if (!currentAclEntries.contains(entry)) {
			throw new IllegalStateException("ACL entry not found: " + entry);
		}
		return new SkillAclRevokedEvent(
				aggregateId, cmd.type(), cmd.principal(), cmd.permission(), cmd.revokedBy());
	}

	public String aggregateId() {
		return aggregateId;
	}

	public long nextSequence() {
		return latestSequence + 1;
	}

	/** S018：當前 aggregate state（replay 後）。 */
	public SkillStatus status() {
		return status;
	}

	private static String entry(String type, String principal, String permission) {
		return type + ":" + principal + ":" + permission;
	}

	/** 從 SkillAclGranted/Revoked event payload 重組 entry 字串（給 replay constructor 用）。 */
	private static String formatEntry(Map<String, Object> payload) {
		return entry(
				(String) payload.get("type"),
				(String) payload.get("principal"),
				(String) payload.get("permission"));
	}

}
