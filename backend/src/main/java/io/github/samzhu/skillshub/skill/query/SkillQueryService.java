package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.shared.api.SkillSuspendedException;
import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * 技能查詢服務 — CQRS 讀取端的核心入口（Spring Data JDBC + PostgreSQL）。
 *
 * <p>S024 ship 後改為直接打 {@link SkillRepository} / {@link SkillVersionRepository} 兩個
 * aggregate repository（取代既有 SkillReadModelRepository / SkillVersionReadModelRepository
 * 的 read-model 設計；ADR-002 §2.4 — 「single skills row 同時為 write model + read model」）。
 *
 * <p>動態關鍵字搜尋仍用 {@link NamedParameterJdbcTemplate}（Spring Data JDBC derived query
 * 無法表達 optional filters + dynamic sort），但 row mapping 改用 {@link Skill#fromRow}
 * 物化 aggregate 物件，與 {@code findById} 等 path 共用同一回傳型別。
 *
 * <p>下載操作改走 aggregate 充血方法 {@link Skill#recordDownload}：
 * load → mutate downloadCount + register {@code SkillDownloadedEvent} → save。
 * Spring Data JDBC 透過 {@code @DomainEvents} 自動 publish 至 Modulith outbox；
 * 既有 v1.5.0 直接 publishEvent + 寫 domain_events 路徑同 T05B 一併移除。
 */
@Service
public class SkillQueryService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * Sort 屬性白名單 — 防 SQL 注入（dynamic ORDER BY 必須白名單比對）。
	 * 使用 Skill aggregate 的 Java 屬性名（camelCase），對應到 SQL snake_case 欄位。
	 */
	private static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"name", "createdAt", "updatedAt", "downloadCount", "category", "status",
			// S100b: enable risk-low sort for HomePage 風險低 chip
			"riskLevel");

	// raw JDBC rowMapper 不會走 Spring Data JDBC 的 user converter；
	// acl_entries（List<String>）需手動 JSONB → List<String> 反序列化，故注入 ObjectMapper。
	private static final TypeReference<List<String>> ACL_ENTRIES_TYPE = new TypeReference<>() {};

	private final SkillRepository skillRepo;
	private final SkillVersionRepository skillVersionRepo;
	private final NamedParameterJdbcTemplate jdbc;
	private final StorageService storageService;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher eventPublisher;
	private final CurrentUserProvider currentUserProvider;
	private final AclPrincipalExpander aclExpander;

	public SkillQueryService(SkillRepository skillRepo, SkillVersionRepository skillVersionRepo,
			NamedParameterJdbcTemplate jdbc, StorageService storageService,
			ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher,
			CurrentUserProvider currentUserProvider, AclPrincipalExpander aclExpander) {
		this.skillRepo = skillRepo;
		this.skillVersionRepo = skillVersionRepo;
		this.jdbc = jdbc;
		this.storageService = storageService;
		this.objectMapper = objectMapper;
		this.eventPublisher = eventPublisher;
		this.currentUserProvider = currentUserProvider;
		this.aclExpander = aclExpander;
	}

	/**
	 * 依 ID 查詢單一技能。
	 *
	 * @param id 技能的 aggregate ID
	 * @return 技能 aggregate
	 * @throws NoSuchElementException 找不到該技能
	 */
	public Skill findById(String id) {
		return skillRepo.findById(id)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));
	}

	/**
	 * S096c — 依 (author, name) 查詢 Skill (canonical route per ADR-003).
	 * case-insensitive；找不到回 {@link NoSuchElementException} → 404 by GlobalExceptionHandler.
	 */
	public Skill findByAuthorAndName(String author, String name) {
		return skillRepo.findByAuthorAndName(author, name)
				.orElseThrow(() -> new NoSuchElementException(
						"Skill not found: " + author + "/" + name));
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
	public Page<Skill> search(String keyword, String category, String author, Pageable pageable) {
		// S031: 公開查詢只回 PUBLISHED — DRAFT（owner 未發版）與 SUSPENDED（admin 下架）皆隱藏。
		// S094a: 帶 author 參數時改為「該 author 全狀態」(P6 作者視角)，跳過 PUBLISHED filter 讓作者看
		// 自己的 DRAFT / SUSPENDED；不帶 author 則維持 S031 公開查詢只露 PUBLISHED 行為。
		var authorMode = StringUtils.hasText(author);
		var statusClause = authorMode
				? " WHERE LOWER(author) = LOWER(:author) "
				: " WHERE status = 'PUBLISHED' ";
		// S121: row-level ACL filter — 補上 list endpoint 漏裝的 acl_entries 過濾。
		// S116 visibility (PUBLIC/PRIVATE) 仰賴 acl_entries 含 *:read 與否判斷可見性，
		// list 必套此 clause 才會生效。expand("read") 對 read permission 自動加 *:read（S026），
		// PUBLIC skill (acl_entries 含 *:read) 對任何 user 可見；PRIVATE 僅 owner / 被 grant
		// 的 principals / role / group 可見。對齊 S016 SkillPermissionStrategy 既驗 ??| ARRAY pattern。
		// Admin role 不在此處特殊化 — admin 若要看 PRIVATE skill 須走 grant；對齊既有 S016 設計
		// （admin bypass 集中在 DelegatingPermissionEvaluator @PreAuthorize 路徑，CQRS read 路徑不另立例外）。
		var aclPatterns = aclExpander.expand(currentUserProvider.current(), "read");
		// S016: ?? escape 必要 — pgJDBC 仍會 parse ? 為 placeholder；?? → ? 後送 ?| operator
		var aclClause = " AND acl_entries ??| :aclPatterns ";
		var sql = new StringBuilder("""
				SELECT id, name, description, author, category,
				       latest_version, risk_level, status, download_count,
				       created_at, updated_at, acl_entries, version
				  FROM skills
				""").append(statusClause).append(aclClause);
		var countSql = new StringBuilder("SELECT COUNT(*) FROM skills")
				.append(statusClause).append(aclClause);
		var params = new MapSqlParameterSource()
				// S016 §2.4 #3: SqlParameterValue(Types.ARRAY, ...) 強制走 ps.setArray()
				// 避免 NamedParameterJdbcTemplate 自動展 String[] 為 IN-list 破壞 ?| 單一 ARRAY 語意
				.addValue("aclPatterns", new SqlParameterValue(Types.ARRAY,
						aclPatterns.toArray(new String[0])));
		if (authorMode) {
			params.addValue("author", author);
		}

		if (StringUtils.hasText(keyword)) {
			// S043: 加入 category 比對 — user 在搜尋框輸入 category 名（如「DevOps」）即可命中對應分類所有 skill
			// （對齊 GitHub / npm 等 catalog 通用 search 慣例）。`?category=` 顯式 filter 仍維持精確 match。
			// S044: trim leading/trailing whitespace — user 複製貼上常含尾空白；trim 屬 input 預處理
			// 與 sanitizeLikePattern（%/_/\ 跳脫）職責正交。
			var trimmed = keyword.trim();
			var clause = " AND (LOWER(name) LIKE LOWER(:kw) ESCAPE '\\' "
					+ "OR LOWER(description) LIKE LOWER(:kw) ESCAPE '\\' "
					+ "OR LOWER(category) LIKE LOWER(:kw) ESCAPE '\\') ";
			sql.append(clause);
			countSql.append(clause);
			params.addValue("kw", "%" + sanitizeLikePattern(trimmed) + "%");
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
				.addKeyValue("author", author)
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
			return "ORDER BY created_at DESC"; // 安全預設
		}
		var parts = new ArrayList<String>();
		// LinkedHashSet 保留順序但去重（防同欄位多次注入）
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

	private Skill mapSkillRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		var versionVal = rs.getObject("version");
		Long version = versionVal == null ? null : ((Number) versionVal).longValue();
		return Skill.fromRow(
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
				rs.getTimestamp("updated_at").toInstant(),
				parseAclEntries(rs.getString("acl_entries")),
				version);
	}

	/**
	 * 將 JSONB 字串反序列化為 {@code List<String>} —
	 * raw JDBC rowMapper 不過 Spring Data JDBC 的 reading converter，故須手動處理。
	 * null / 空 / blank 還原為 {@link List#of()}（fail-secure 對齊
	 * {@code StringListJsonbConverter.Reading} 行為）。
	 */
	private List<String> parseAclEntries(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, ACL_ENTRIES_TYPE);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse acl_entries JSONB: " + json, e);
		}
	}

	/**
	 * 查詢某技能的所有版本，按發佈時間降序排列。
	 *
	 * @param skillId 技能 ID
	 * @return 版本列表（最新在前）
	 */
	public List<SkillVersion> findVersionsBySkillId(String skillId) {
		return skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
	}

	/**
	 * 下載某技能的最新版本 zip，並透過 aggregate 充血方法觸發下載事件。
	 *
	 * @param skillId 技能 ID
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 該技能沒有任何已發佈版本
	 */
	@Transactional
	public byte[] downloadLatest(String skillId) {
		var versions = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		if (versions.isEmpty()) {
			throw new NoSuchElementException("No versions found for skill: " + skillId);
		}
		return downloadAndRecord(skillId, versions.getFirst());
	}

	/**
	 * 下載某技能的指定版本 zip，並透過 aggregate 充血方法觸發下載事件。
	 *
	 * @param skillId 技能 ID
	 * @param version 指定版本號（如 "1.0.0"）
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 找不到該版本
	 */
	@Transactional
	public byte[] downloadVersion(String skillId, String version) {
		var target = skillVersionRepo.findBySkillIdAndVersion(skillId, version)
				.orElseThrow(() -> new NoSuchElementException("Version " + version + " not found"));
		return downloadAndRecord(skillId, target);
	}

	/**
	 * S024 T05B：下載成功 + 透過 aggregate 充血方法計數 + register {@code SkillDownloadedEvent}。
	 * Spring Data JDBC {@code skillRepo.save} 透過 {@code @DomainEvents} 自動 publish 至 Modulith
	 * outbox，由 AnalyticsProjection / AuditEventListener 等 listener 接收。
	 */
	private byte[] downloadAndRecord(String skillId, SkillVersion version) {
		// S029: SUSPENDED skill 不可下載（per SkillStatus.SUSPENDED Javadoc）。
		// fail-fast：先 findById + status guard，避免無謂 storage download bandwidth。
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
		if (skill.getStatus() == SkillStatus.SUSPENDED) {
			throw new SkillSuspendedException(skillId);
		}
		var zipBytes = storageService.download(version.getStoragePath());

		// S076: 原子計數遞增（非 aggregate read-modify-write），避免並行下載 OptimisticLockingFailureException
		// 級聯（pre-S076: N=2 → 50% 失敗，N=10 → 90% 失敗）。Counter 不是 state-machine concern，PG row-level
		// lock 即正確語意。事件改用 ApplicationEventPublisher 顯式發；Modulith Event Publication Registry
		// 攔截 transactional context 任何 event，outbox at-least-once 不變。
		// `recordDownload()` aggregate method 仍保留供單元測試與其他 caller（若有）使用。
		skillRepo.incrementDownloadCount(skillId, Instant.now());
		eventPublisher.publishEvent(SkillDownloadedEvent.of(skillId, version.getVersion()));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version.getVersion())
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
		// S031: 與 search() 一致，只計 PUBLISHED；避免 sidebar 顯示「DevOps (16)」但 list 只給 13 的不一致
		return jdbc.query("""
				SELECT category AS name, COUNT(*) AS count
				  FROM skills
				 WHERE category IS NOT NULL
				   AND status = 'PUBLISHED'
				 GROUP BY category
				 ORDER BY count DESC
				""",
				new MapSqlParameterSource(),
				(rs, rowNum) -> new CategoryCount(rs.getString("name"), rs.getLong("count")));
	}

}
