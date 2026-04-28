package io.github.samzhu.skillshub.skill.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;
import io.github.samzhu.skillshub.skill.command.VersionExistsException;

/**
 * Skill Aggregate Root — 封裝業務規則、產生 domain events。
 *
 * <p>S016 起加入 ACL 部分狀態（{@code currentAclEntries}），用以驗 grant/revoke 的業務不變量
 * 「無重複 grant」與「revoke 必須有對應 entry」。其他狀態（如 published versions）保持 minimal
 * replay；未來 ES-B1（Event Replay）可擴展為完整重建。
 */
public class Skill {

	private final String aggregateId;
	private final Set<String> publishedVersions;
	private final Set<String> currentAclEntries;
	private final long latestSequence;

	/**
	 * 從 event store 載入 aggregate — replay 累積版本號 + ACL entries + 最新 sequence。
	 */
	public Skill(String aggregateId, List<DomainEvent> events) {
		this.aggregateId = aggregateId;
		this.publishedVersions = new HashSet<>();
		this.currentAclEntries = new HashSet<>();
		long maxSeq = 0;
		for (var event : events) {
			switch (event.eventType()) {
				case "SkillVersionPublished" ->
					publishedVersions.add((String) event.payload().get("version"));
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
	 * 發佈新版本 — 驗證版本不重複，回傳 SkillVersionPublished event
	 */
	public SkillVersionPublishedEvent publishVersion(String version, String storagePath,
			long fileSize, Map<String, Object> frontmatter) {
		if (publishedVersions.contains(version)) {
			throw new VersionExistsException("Version " + version + " already exists");
		}
		return new SkillVersionPublishedEvent(aggregateId, version, storagePath, fileSize, frontmatter);
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
