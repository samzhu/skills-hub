package io.github.samzhu.skillshub.security;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.FlagNotFoundException;
import io.github.samzhu.skillshub.shared.api.InvalidStatusTransitionException;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * Flag（舉報）應用服務，負責協調 Flag 的建立流程與查詢操作。
 *
 * <p>建立 Flag 時遵循 Event Sourcing 流程：</p>
 * <ol>
 *   <li>產生領域事件並持久化至 {@code domain_events}</li>
 *   <li>更新讀取模型（{@code flags} Collection）</li>
 *   <li>透過 {@code ApplicationEventPublisher} 發布 {@link SkillFlaggedEvent}</li>
 * </ol>
 */
@Service
public class FlagService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * S072: 合法 flag type 白名單 — 對齊 docs/grimo/glossary.md 與前端後續 flag UI 的 enum。
	 * 缺白名單時 admin review 困難（任意字串都能進 DB），且 spam vector。
	 */
	private static final Set<String> ALLOWED_TYPES = Set.of(
			"malicious", "spam", "inappropriate", "copyright", "security", "other");

	/** S072: description 上限 500 字元 — 防 DB 撐爆與 UI 排版破壞。 */
	private static final int DESCRIPTION_MAX = 500;

	private final FlagReadModelRepository flagRepo;
	private final DomainEventRepository eventStore;
	private final NamedParameterJdbcTemplate jdbc;
	private final ApplicationEventPublisher events;

	public FlagService(FlagReadModelRepository flagRepo, DomainEventRepository eventStore,
			NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
		this.flagRepo = flagRepo;
		this.eventStore = eventStore;
		this.jdbc = jdbc;
		this.events = events;
	}

	/**
	 * 建立一筆新的 Flag（舉報），並依 Event Sourcing 流程持久化後發布領域事件。
	 *
	 * <p>S098e3 AC-1：加 reporter 參數紀錄真實 submitter；null/blank 走 anonymous fallback
	 * 對齊 LAB mode 行為。
	 *
	 * @param skillId     被舉報的 Skill 識別碼
	 * @param type        舉報類型代碼，例如 {@code malicious}
	 * @param description 舉報原因說明
	 * @param reporter    舉報人識別（null/blank → "anonymous" fallback）
	 * @return 新建立的 Flag UUID 字串
	 */
	@Transactional
	public String createFlag(String skillId, String type, String description, String reporter) {
		// S058: 預驗 — type 為 DB NOT NULL varchar(20)；description nullable；
		// Map.of 不接受 null values 也是 NPE 來源
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("Flag type must not be blank");
		}
		var trimmedType = type.trim();
		if (trimmedType.length() > 20) {
			throw new IllegalArgumentException(
					"Flag type exceeds 20 characters (got: " + trimmedType.length() + ")");
		}
		// S072: type 白名單 — 沒這道閘任何字串都進 DB（"bogus"、"x"），admin review 不可能
		if (!ALLOWED_TYPES.contains(trimmedType)) {
			throw new IllegalArgumentException(
					"Flag type must be one of " + ALLOWED_TYPES + " (got: " + trimmedType + ")");
		}
		var trimmedDescription = description == null ? null : description.trim();
		// S072: description 長度上限 — 5000-char 描述能進 DB，UI 排版破壞 + 儲存成本
		if (trimmedDescription != null && trimmedDescription.length() > DESCRIPTION_MAX) {
			throw new IllegalArgumentException(
					"Flag description exceeds " + DESCRIPTION_MAX + " characters (got: "
							+ trimmedDescription.length() + ")");
		}

		var flagId = UUID.randomUUID().toString();

		// S024 T05B: 與 AuditEventListener 共用 per-aggregate advisory lock 序列化 sequence 計算，
		// 避免 race condition（兩者各自讀 MAX → 算同 next_seq → (aggregate_id, sequence) UNIQUE 衝突）
		jdbc.queryForList(
				"SELECT pg_advisory_xact_lock(hashtext('audit:' || :aggregateId)::bigint)",
				Collections.singletonMap("aggregateId", skillId));

		var latestEvent = eventStore.findTopByAggregateIdOrderBySequenceDesc(skillId);
		long nextSequence = latestEvent.map(e -> e.sequence() + 1).orElse(1L);

		// S058: 改 HashMap allow null description（Map.of 不接受 null values）
		var payload = new java.util.HashMap<String, Object>();
		payload.put("flagId", flagId);
		payload.put("type", trimmedType);
		if (trimmedDescription != null) {
			payload.put("description", trimmedDescription);
		}

		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(),
				skillId,
				"Skill",
				"SkillFlagged",
				payload,
				nextSequence,
				Instant.now(),
				Map.of()
		);
		eventStore.save(domainEvent);

		// S098e3 AC-1/AC-2：reporter null/blank → anonymous fallback（保留 LAB mode 行為）
		var resolvedReporter = (reporter == null || reporter.isBlank()) ? "anonymous" : reporter.trim();
		var flag = new FlagReadModel(flagId, skillId, trimmedType, trimmedDescription, resolvedReporter, Instant.now(), "OPEN");
		flagRepo.save(flag);

		events.publishEvent(new SkillFlaggedEvent(skillId, trimmedType, trimmedDescription, resolvedReporter));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("type", trimmedType)
				.addKeyValue("flagId", flagId)
				.log("Flag 建立成功");

		return flagId;
	}

	/**
	 * 查詢指定 Skill 的所有 Flag，依建立時間降冪排序。
	 *
	 * @param skillId 目標 Skill 的識別碼
	 * @return Flag 清單，若無則回傳空列表
	 */
	public List<FlagReadModel> getFlagsBySkillId(String skillId) {
		return flagRepo.findBySkillIdOrderByCreatedAtDesc(skillId);
	}

	/**
	 * S112: 統計指定 author 名下所有 PUBLISHED skill 的 OPEN flag 總數。
	 *
	 * <p>用於 MySkillsPage「待處理回報」MetricCard，避免前端 N+1 fetch。
	 * 跨 {@code flags} + {@code skills} 兩表，故走原生 SQL 而非 derived query。
	 *
	 * @param author 作者帳號（對應 {@code skills.author}）
	 * @return 該 author 名下 PUBLISHED skill 的 OPEN flag 總數；無資料時回 0
	 */
	public long countOpenFlagsForAuthor(String author) {
		var sql = """
				SELECT COUNT(*) FROM flags f
				WHERE f.status = 'OPEN'
				  AND f.skill_id IN (
				      SELECT id FROM skills
				      WHERE author = :author AND status = 'PUBLISHED'
				  )
				""";
		var params = Map.of("author", author);
		Long count = jdbc.queryForObject(sql, params, Long.class);
		return count == null ? 0L : count;
	}

	/** S098e3 AC-5：per-skill 加 status filter；null/blank 等價無 filter。 */
	public List<FlagReadModel> getFlagsBySkillId(String skillId, String status) {
		if (status == null || status.isBlank()) {
			return flagRepo.findBySkillIdOrderByCreatedAtDesc(skillId);
		}
		return flagRepo.findBySkillIdAndStatusOrderByCreatedAtDesc(skillId, status);
	}

	/** S098e3 AC-3 / AC-4：cross-skill 列表；null/blank 回全部。 */
	public List<FlagReadModel> listAllFlags(String status) {
		if (status == null || status.isBlank()) {
			return flagRepo.findAllByOrderByCreatedAtDesc();
		}
		return flagRepo.findByStatusOrderByCreatedAtDesc(status);
	}

	/**
	 * S098e3 AC-6/7/8 — Flag status mutation。
	 *
	 * @throws FlagNotFoundException            AC-8 flag id 不存在
	 * @throws InvalidStatusTransitionException AC-7 transition 違規或 newStatus 不合法
	 */
	@Transactional
	public void updateStatus(String flagId, String newStatusName, String actor) {
		// AC-7：parse + validate
		var newStatus = FlagStatus.fromName(newStatusName);
		if (newStatus == null) {
			throw new InvalidStatusTransitionException(
					"unknown flag status: " + newStatusName + " (allowed: OPEN, RESOLVED, DISMISSED)");
		}

		// AC-8：load existing
		var current = flagRepo.findById(flagId)
				.orElseThrow(() -> new FlagNotFoundException(flagId));

		// AC-7：transition check — 走 enum state machine
		var currentStatus = FlagStatus.fromName(current.status());
		if (currentStatus == null) {
			throw new IllegalStateException(
					"corrupt DB state: flag " + flagId + " has unknown status " + current.status());
		}
		if (!currentStatus.canTransitionTo(newStatus)) {
			throw new InvalidStatusTransitionException(
					"cannot transition flag " + flagId + " from " + currentStatus + " to " + newStatus);
		}

		// AC-6：UPDATE via @Modifying @Query（FlagReadModel.isNew=true 不能用 save）
		int updated = flagRepo.updateStatus(flagId, newStatus.name());
		if (updated == 0) {
			// 罕見 race：load 成功但 update affected=0；當 not-found 處理
			throw new FlagNotFoundException(flagId);
		}

		var resolvedActor = (actor == null || actor.isBlank()) ? "anonymous" : actor.trim();
		events.publishEvent(new FlagStatusChangedEvent(
				flagId, current.skillId(), currentStatus, newStatus, resolvedActor, Instant.now()));

		log.atInfo()
				.addKeyValue("flagId", flagId)
				.addKeyValue("oldStatus", currentStatus)
				.addKeyValue("newStatus", newStatus)
				.addKeyValue("actor", resolvedActor)
				.log("Flag status changed");
	}

}
