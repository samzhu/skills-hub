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
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * 技能查詢側投影 — 消費領域事件，維護 {@code skills} 和 {@code skill_versions} 讀取模型。
 *
 * <p>S023 採 <b>hybrid listener migration</b>（per ADR-002 + S023 spec §2.2）：
 * <ul>
 *   <li><b>FK target row 創建者</b>（保留同步 {@code @EventListener} + {@code @Order(HIGHEST_PRECEDENCE)}）：
 *     {@link #on(SkillCreatedEvent)} 與 {@link #on(SkillVersionPublishedEvent)} —
 *     在 publisher TX 內同步寫 {@code skills} / {@code skill_versions} row，
 *     確保後續 AFTER_COMMIT async listener（SearchProjection / ScanOrchestrator）
 *     的 FK reference 已存在。S024 廢除（Skill/SkillVersion 變 stateful aggregate
 *     自己 INSERT row）。</li>
 *   <li><b>非 FK-creating handlers</b>（升級為 {@code @ApplicationModuleListener}）：
 *     5 個 method（download / aclGranted / aclRevoked / suspended / reactivated）—
 *     async + AFTER_COMMIT + REQUIRES_NEW + outbox 追蹤。失敗 → status='FAILED'
 *     可由 IncompleteEventRepublishTask retry。</li>
 * </ul>
 *
 * <p>處理的事件：</p>
 * <ul>
 *   <li>{@link SkillCreatedEvent} → 建立 {@link SkillReadModel}（初始狀態 DRAFT）— sync</li>
 *   <li>{@link SkillVersionPublishedEvent} → 更新 latestVersion + 新增 {@link SkillVersionReadModel}；首版觸發 status DRAFT→PUBLISHED（S018 BUG fix）— sync</li>
 *   <li>{@link SkillDownloadedEvent} → 累加 downloadCount — async</li>
 *   <li>{@link SkillAclGrantedEvent} → 將 {@code "type:principal:permission"} 字串 append 到 acl_entries（冪等）— async</li>
 *   <li>{@link SkillAclRevokedEvent} → 從 acl_entries 移除指定字串 — async</li>
 *   <li>{@link SkillSuspendedEvent} → status='SUSPENDED'（S018）— async</li>
 *   <li>{@link SkillReactivatedEvent} → status='PUBLISHED'（S018）— async</li>
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
		var now = Instant.now();
		// 更新 skills read model 的最新版本 — atomic UPDATE，避免 Spring Data JDBC 對
		// record + non-null id 的「save → UPDATE」誤判路徑（既有 read-modify-write 在
		// Persistable.isNew()=true 後會走 INSERT 變 PK 衝突）
		repo.updateLatestVersion(event.aggregateId(), event.version(), now);

		// S018 AC-2 BUG fix：首版發布觸發 status DRAFT→PUBLISHED（idempotent for 後續發版 — UPDATE
		// 設成 PUBLISHED 不論原 status 為何；DRAFT 升級、PUBLISHED 維持、SUSPENDED 該不轉但
		// publishVersion 在 aggregate 端已被 state machine guard 擋下不會走到這裡）
		repo.updateStatus(event.aggregateId(), SkillStatus.PUBLISHED.name(), now);

		// 建立版本 read model 記錄；riskAssessment 在此為 null，由 S010 ScanOrchestrator
		// 完成多引擎掃描後透過 SkillVersionReadModelRepository.updateRiskAssessment 寫入。
		// S018 AC-13: allowedTools 為 first-class field（既有 events replay 時可能 null → fallback empty）
		var allowedTools = event.allowedTools() != null ? event.allowedTools() : List.<String>of();
		var versionEntry = new SkillVersionReadModel(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				event.version(),
				event.storagePath(),
				event.fileSize(),
				event.frontmatter(),
				null,
				now,
				allowedTools
		);
		versionRepo.save(versionEntry);

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("version", event.version())
				.log("投影已更新版本記錄 + status 轉為 PUBLISHED");
	}

	/**
	 * 下載事件 → 累加 download_count（atomic UPDATE，避免 race condition）。
	 *
	 * <p>S014 從 read-modify-write 改 atomic UPDATE — 既有實作在 Mongo 上有
	 * race condition（兩個並發 download 可能丟一次計數），改用 SQL 原生表達式
	 * {@code SET download_count = download_count + 1} 由 PostgreSQL row-level lock
	 * 保證原子性。
	 *
	 * <p>S023：升級為 {@code @ApplicationModuleListener}（async + AFTER_COMMIT +
	 * REQUIRES_NEW + outbox 追蹤）。<b>未加 idempotency 檢查</b>：spec §4.8 原設計
	 * 用 {@code download_events.event_id NOT EXISTS} 子查詢有 race condition
	 * （與 AnalyticsProjection async 並行；若 Analytics 先 INSERT，Skill 看到 row 存在
	 * 會錯誤跳過增量）。改採「接受極罕見的 markCompleted-fail-then-retry 雙計」設計 —
	 * 下載計數為 UI 顯示，非財務性，rare double-count 可接受。詳 T02 task result。
	 */
	@ApplicationModuleListener
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
	 *
	 * <p>S023：升級為 {@code @ApplicationModuleListener}。{@code appendAclEntry} SQL 含
	 * {@code WHERE NOT (acl_entries @> to_jsonb(:entry))} → 重投同 entry 不疊加，原生冪等。
	 */
	@ApplicationModuleListener
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
	 *
	 * <p>S023：升級為 {@code @ApplicationModuleListener}。{@code removeAclEntry} SQL 用
	 * {@code jsonb_agg WHERE elem != :entry} → 重投不存在 entry 結果不變，原生冪等。
	 */
	@ApplicationModuleListener
	void on(SkillAclRevokedEvent event) {
		var entry = event.type() + ":" + event.principal() + ":" + event.permission();
		var rows = repo.removeAclEntry(event.aggregateId(), entry, Instant.now());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("entry", entry)
				.addKeyValue("rowsAffected", rows)
				.log("投影已 remove ACL entry");
	}

	/**
	 * S018：Skill 停用事件 → read model status='SUSPENDED'。
	 *
	 * <p>S023：升級為 {@code @ApplicationModuleListener}。{@code updateStatus} 為 unconditional
	 * SET → 重投寫同值無副作用，原生冪等。
	 *
	 * <p><b>安全敏感性提示</b>：suspend 為緊急下架操作；async + AFTER_COMMIT 表示 commit
	 * 後到 read model 更新前有毫秒級時窗，該 skill 仍顯示 PUBLISHED 狀態可被下載。
	 * 若日後安全要求要強一致 suspend，需考慮專屬 sync listener 或 DB-level constraint。
	 */
	@ApplicationModuleListener
	void on(SkillSuspendedEvent event) {
		var rows = repo.updateStatus(event.aggregateId(), SkillStatus.SUSPENDED.name(), Instant.now());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("reason", event.reason())
				.addKeyValue("suspendedBy", event.suspendedBy())
				.addKeyValue("rowsAffected", rows)
				.log("投影已將 status 更新為 SUSPENDED");
	}

	/**
	 * S018：Skill 重啟事件 → read model status='PUBLISHED'。
	 *
	 * <p>S023：升級為 {@code @ApplicationModuleListener}。{@code updateStatus} 為 unconditional
	 * SET → 重投寫同值無副作用，原生冪等。
	 */
	@ApplicationModuleListener
	void on(SkillReactivatedEvent event) {
		var rows = repo.updateStatus(event.aggregateId(), SkillStatus.PUBLISHED.name(), Instant.now());

		log.atInfo()
				.addKeyValue("skillId", event.aggregateId())
				.addKeyValue("reason", event.reason())
				.addKeyValue("rowsAffected", rows)
				.log("投影已將 status 更新為 PUBLISHED");
	}

}
