package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent;
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
 *   <li>{@link SkillAclGrantedEvent} → 將 {@code "type:principal:permission"} 字串 append 到 acl_entries（冪等）</li>
 *   <li>{@link SkillAclRevokedEvent} → 從 acl_entries 移除指定字串</li>
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

	/**
	 * 技能建立 → 初始化 read model，狀態設為 DRAFT，下載數為 0。
	 *
	 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 確保在 SearchProjection.onSkillCreated
	 * （S014 T7 後兩步驟寫入 vector_store，第二步 UPDATE 寫 {@code skill_id}
	 * FK 至 skills.id）之前執行。skills row 必須先存在，否則 vector_store FK violation。
	 */
	@EventListener
	@Order(Ordered.HIGHEST_PRECEDENCE)
	void on(SkillCreatedEvent event) {
		var now = Instant.now();
		// S016 T3: 新 skill 建立時 author 即 owner — 同 V2 backfill 邏輯（per spec §4.2 + §3 AC-7
		// BDD 預設 acl_entries 已含 user:<author>:read|write|delete）。@PreAuthorize 對 PUT /versions
		// 套上後，若創建端不 seed ACL，作者自己也無法 PUT 自己的 skill。
		var ownerAclEntries = List.of(
				"user:" + event.author() + ":read",
				"user:" + event.author() + ":write",
				"user:" + event.author() + ":delete");

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
				now,
				ownerAclEntries
		);
		repo.save(readModel);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("name", event.name())
				.log("投影已建立技能 read model");
	}

	/**
	 * 版本發佈 → 更新 skills 的 latestVersion + 新增 skill_versions 記錄。
	 *
	 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 確保在 ScanOrchestrator (S010, security::scan) 之前執行：
	 * ScanOrchestrator 透過 {@code SkillVersionReadModelRepository.updateRiskAssessment}
	 * （@Modifying @Query）寫 risk_assessment 欄位，需要本 listener 先建立 skill_versions row
	 * （依 (skill_id, version) 找）才有效。Spring 同步 @EventListener 預設 priority 同為
	 * LOWEST_PRECEDENCE，順序未定；顯式排序避免種族條件。
	 */
	@EventListener
	@Order(Ordered.HIGHEST_PRECEDENCE)
	void on(SkillVersionPublishedEvent event) {
		// 更新 skills read model 的最新版本 — atomic UPDATE，避免 Spring Data JDBC 對
		// record + non-null id 的「save → UPDATE」誤判路徑（既有 read-modify-write 在
		// Persistable.isNew()=true 後會走 INSERT 變 PK 衝突）
		repo.updateLatestVersion(event.aggregateId(), event.version(), Instant.now());

		// 建立版本 read model 記錄；riskAssessment 在此為 null，由 S010 ScanOrchestrator
		// 完成多引擎掃描後透過 SkillVersionReadModelRepository.updateRiskAssessment 寫入。
		var versionEntry = new SkillVersionReadModel(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				event.version(),
				event.storagePath(),
				event.fileSize(),
				event.frontmatter(),
				null,
				Instant.now()
		);
		versionRepo.save(versionEntry);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("投影已更新版本記錄");
	}

	/**
	 * 下載事件 → 累加 download_count（atomic UPDATE，避免 race condition）。
	 *
	 * <p>S014 從 read-modify-write 改 atomic UPDATE — 既有實作在 Mongo 上有
	 * race condition（兩個並發 download 可能丟一次計數），改用 SQL 原生表達式
	 * {@code SET download_count = download_count + 1} 由 PostgreSQL row-level lock
	 * 保證原子性。
	 */
	@EventListener
	void on(SkillDownloadedEvent event) {
		repo.incrementDownloadCount(event.aggregateId(), Instant.now());

		log.atDebug()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("投影已累加下載次數（atomic）");
	}

	/**
	 * S016：ACL 授權事件 → atomic append 至 {@code skills.acl_entries}（冪等）。
	 *
	 * <p>0 row 影響時不 throw — 可能因 entry 已存在（重送 event）或 skill row 不存在
	 * （read-side 落後 / 異常順序）；aggregate 端已驗業務 invariant，此處只負責 read model 一致性。
	 */
	@EventListener
	void on(SkillAclGrantedEvent event) {
		var entry = event.type() + ":" + event.principal() + ":" + event.permission();
		var rows = repo.appendAclEntry(event.aggregateId(), entry, Instant.now());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("entry", entry)
				.addKeyValue("rowsAffected", rows)
				.log("投影已 append ACL entry");
	}

	/**
	 * S016：ACL 撤銷事件 → 從 {@code skills.acl_entries} 移除指定字串。
	 *
	 * <p>jsonb_array_elements_text 重組策略對「entry 不存在」結果不變；不視為錯誤。
	 */
	@EventListener
	void on(SkillAclRevokedEvent event) {
		var entry = event.type() + ":" + event.principal() + ":" + event.permission();
		var rows = repo.removeAclEntry(event.aggregateId(), entry, Instant.now());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("entry", entry)
				.addKeyValue("rowsAffected", rows)
				.log("投影已 remove ACL entry");
	}

}
