package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * 技能查詢服務 — CQRS 讀取端的核心入口（Spring Data JDBC + PostgreSQL）。
 *
 * <p>負責：關鍵字搜尋、分類篩選、技能詳情查詢、版本歷史、下載（含事件記錄）。
 * 動態搜尋使用 {@link NamedParameterJdbcTemplate} 動態組 SQL，
 * 因為 Spring Data JDBC 的 derived query 無法表達 optional filters + dynamic sort。
 *
 * <p>下載操作雖然是讀取，但附帶副作用（記錄 {@link SkillDownloadedEvent}），
 * 因此在此服務中同時處理讀取 + 事件發佈。
 *
 * <p>S014 從 Firestore（MongoTemplate）遷至 PostgreSQL（NamedParameterJdbcTemplate）。
 */
@Service
public class SkillQueryService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * Sort 屬性白名單 — 防 SQL 注入（dynamic ORDER BY 必須白名單比對）。
	 * 使用 SkillReadModel 的 Java 屬性名（camelCase），對應到 SQL snake_case 欄位。
	 */
	private static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"name", "createdAt", "updatedAt", "downloadCount", "category", "status");

	private final SkillReadModelRepository repo;
	private final SkillVersionReadModelRepository versionRepo;
	private final NamedParameterJdbcTemplate jdbc;
	private final StorageService storageService;
	private final DomainEventRepository eventStore;
	private final ApplicationEventPublisher events;

	public SkillQueryService(SkillReadModelRepository repo, SkillVersionReadModelRepository versionRepo,
			NamedParameterJdbcTemplate jdbc, StorageService storageService,
			DomainEventRepository eventStore, ApplicationEventPublisher events) {
		this.repo = repo;
		this.versionRepo = versionRepo;
		this.jdbc = jdbc;
		this.storageService = storageService;
		this.eventStore = eventStore;
		this.events = events;
	}

	/**
	 * 依 ID 查詢單一技能的 read model。
	 *
	 * @param id 技能的 aggregate ID
	 * @return 技能讀取模型
	 * @throws NoSuchElementException 找不到該技能
	 */
	public SkillReadModel findById(String id) {
		return repo.findById(id)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));
	}

	/**
	 * 關鍵字 + 分類組合搜尋（動態 SQL）。
	 *
	 * <p>keyword 做 case-insensitive LIKE 比對，同時匹配 name 和 description（OR）；
	 * category 做精確比對。兩者皆為可選，都不帶則回傳全部。
	 *
	 * <p>SQL 注入防護：所有輸入透過 {@link MapSqlParameterSource} 參數化；LIKE 通配符
	 * （{@code % _ \}）由 {@link #sanitizeLikePattern} 跳脫；ORDER BY 屬性透過
	 * {@link #SORTABLE_PROPERTIES} 白名單比對。
	 *
	 * @param keyword  關鍵字（可選）
	 * @param category 分類名稱（可選）
	 * @param pageable 分頁 + 排序參數
	 * @return 分頁結果
	 */
	public Page<SkillReadModel> search(String keyword, String category, Pageable pageable) {
		var sql = new StringBuilder("""
				SELECT id, name, description, author, category,
				       latest_version, risk_level, status, download_count,
				       created_at, updated_at
				  FROM skills
				 WHERE 1=1
				""");
		var countSql = new StringBuilder("SELECT COUNT(*) FROM skills WHERE 1=1");
		var params = new MapSqlParameterSource();

		if (StringUtils.hasText(keyword)) {
			var clause = " AND (LOWER(name) LIKE LOWER(:kw) ESCAPE '\\' "
					+ "OR LOWER(description) LIKE LOWER(:kw) ESCAPE '\\') ";
			sql.append(clause);
			countSql.append(clause);
			params.addValue("kw", "%" + sanitizeLikePattern(keyword) + "%");
		}
		if (StringUtils.hasText(category)) {
			sql.append(" AND category = :cat ");
			countSql.append(" AND category = :cat ");
			params.addValue("cat", category);
		}

		sql.append(' ').append(buildOrderByClause(pageable.getSort()));
		sql.append(" LIMIT :limit OFFSET :offset");
		params.addValue("limit", pageable.getPageSize());
		params.addValue("offset", pageable.getOffset());

		var results = jdbc.query(sql.toString(), params, this::mapSkillRow);
		Long total = jdbc.queryForObject(countSql.toString(), params, Long.class);

		log.atDebug()
				.addKeyValue("keyword", keyword)
				.addKeyValue("category", category)
				.addKeyValue("resultCount", results.size())
				.log("技能搜尋完成");

		return new PageImpl<>(results, pageable, total != null ? total : 0L);
	}

	/**
	 * 跳脫 LIKE wildcard（{@code % _ \}）防使用者輸入被當作通配符。
	 * 必須與 SQL 的 {@code ESCAPE '\\'} 子句搭配使用。
	 */
	private static String sanitizeLikePattern(String input) {
		return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/**
	 * 動態 ORDER BY 子句 — 依 Pageable.Sort 組裝，屬性必須在白名單中。
	 * Sort 屬性以 Java camelCase 提供（如 downloadCount），SQL 端需轉為 snake_case。
	 */
	private static String buildOrderByClause(Sort sort) {
		if (sort == null || sort.isUnsorted()) {
			return "ORDER BY created_at DESC"; // 安全預設，與 Firestore 行為一致
		}
		var parts = new ArrayList<String>();
		// 用 LinkedHashSet 保留順序但去重（防同欄位多次注入）
		var seen = new HashSet<String>();
		for (var order : sort) {
			var prop = order.getProperty();
			if (!SORTABLE_PROPERTIES.contains(prop) || !seen.add(prop)) {
				continue; // 不在白名單或已存在 → 忽略（防注入 + idempotent）
			}
			parts.add(toSnakeCase(prop) + " " + (order.isAscending() ? "ASC" : "DESC"));
		}
		return parts.isEmpty() ? "ORDER BY created_at DESC" : "ORDER BY " + String.join(", ", parts);
	}

	/** Java camelCase → SQL snake_case（簡單實作，僅處理白名單已知屬性）。 */
	private static String toSnakeCase(String camel) {
		var sb = new StringBuilder();
		for (int i = 0; i < camel.length(); i++) {
			char c = camel.charAt(i);
			if (Character.isUpperCase(c)) {
				if (i > 0) sb.append('_');
				sb.append(Character.toLowerCase(c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private SkillReadModel mapSkillRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new SkillReadModel(
				rs.getString("id"),
				rs.getString("name"),
				rs.getString("description"),
				rs.getString("author"),
				rs.getString("category"),
				rs.getString("latest_version"),
				rs.getString("risk_level"),
				rs.getString("status"),
				rs.getLong("download_count"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	/**
	 * 查詢某技能的所有版本，按發佈時間降序排列。
	 *
	 * @param skillId 技能 ID
	 * @return 版本列表（最新在前）
	 */
	public List<SkillVersionReadModel> findVersionsBySkillId(String skillId) {
		return versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
	}

	/**
	 * 下載某技能的最新版本 zip，並記錄下載事件。
	 *
	 * @param skillId 技能 ID
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 該技能沒有任何已發佈版本
	 */
	public byte[] downloadLatest(String skillId) {
		var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		if (versions.isEmpty()) {
			throw new NoSuchElementException("No versions found for skill: " + skillId);
		}
		return downloadAndRecord(skillId, versions.getFirst());
	}

	/**
	 * 下載某技能的指定版本 zip，並記錄下載事件。
	 *
	 * @param skillId 技能 ID
	 * @param version 指定版本號（如 "1.0.0"）
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 找不到該版本
	 */
	public byte[] downloadVersion(String skillId, String version) {
		var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		var target = versions.stream()
				.filter(v -> version.equals(v.version()))
				.findFirst()
				.orElseThrow(() -> new NoSuchElementException("Version " + version + " not found"));
		return downloadAndRecord(skillId, target);
	}

	/**
	 * 從 GCS 下載 zip 並發佈 SkillDownloaded 事件（供 analytics projection 消費）。
	 */
	private byte[] downloadAndRecord(String skillId, SkillVersionReadModel version) {
		var zipBytes = storageService.download(version.storagePath());

		// 計算下一個 event sequence — Aggregate 樂觀鎖控制
		var latestEvent = eventStore.findTopByAggregateIdOrderBySequenceDesc(skillId);
		long nextSequence = latestEvent.map(e -> e.sequence() + 1).orElse(1L);

		var payload = Map.<String, Object>of("version", version.version());
		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(), skillId, "Skill", "SkillDownloaded",
				payload, nextSequence, Instant.now(), Map.of());
		eventStore.save(domainEvent);
		events.publishEvent(new SkillDownloadedEvent(skillId, version.version()));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version.version())
				.addKeyValue("fileSize", zipBytes.length)
				.log("技能下載完成，已記錄下載事件");

		return zipBytes;
	}

	/**
	 * 取得所有分類及其技能數量，按數量降序排列。
	 *
	 * <p>SQL：{@code SELECT category AS name, COUNT(*) AS count FROM skills
	 * WHERE category IS NOT NULL GROUP BY category ORDER BY count DESC}。
	 *
	 * @return 分類計數列表
	 */
	public List<CategoryCount> getCategoryCounts() {
		return jdbc.query("""
				SELECT category AS name, COUNT(*) AS count
				  FROM skills
				 WHERE category IS NOT NULL
				 GROUP BY category
				 ORDER BY count DESC
				""",
				new MapSqlParameterSource(),
				(rs, rowNum) -> new CategoryCount(rs.getString("name"), rs.getLong("count")));
	}

}
