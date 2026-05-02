package io.github.samzhu.skillshub.analytics;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 分析（Analytics）應用服務 — 彙整平台統計資料並回傳概覽資訊。
 *
 * <p>資料來源為 PostgreSQL 的 {@code skills} 與 {@code download_events} 表，
 * 均屬於 CQRS 讀取側的 Projection，不直接操作領域聚合。
 *
 * <p>S014 從 MongoTemplate（{@code count} + {@code Aggregation.newAggregation}）
 * 遷至 {@link NamedParameterJdbcTemplate} + 純 SQL（{@code SELECT COUNT} +
 * {@code ORDER BY ... LIMIT}）。
 */
@Service
public class AnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final NamedParameterJdbcTemplate jdbc;

	public AnalyticsService(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 取得平台整體概覽統計，包含 Skill 總數、下載總數、本週新增數量及下載排行榜。
	 *
	 * @return 包含各項統計指標的 {@link OverviewStats}
	 */
	public OverviewStats getOverview() {
		// S031: 公開統計只計 PUBLISHED — DRAFT（未發版）與 SUSPENDED（下架）不該影響「平台規模」呈現
		long totalSkills = countOrZero(
				"SELECT COUNT(*) FROM skills WHERE status = 'PUBLISHED'",
				new MapSqlParameterSource());
		long totalDownloads = countOrZero("SELECT COUNT(*) FROM download_events", new MapSqlParameterSource());

		var oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
		long newSkillsThisWeek = countOrZero(
				"SELECT COUNT(*) FROM skills WHERE created_at >= :since AND status = 'PUBLISHED'",
				new MapSqlParameterSource("since", java.sql.Timestamp.from(oneWeekAgo)));

		var topSkills = getTopSkills(10);

		log.atDebug()
				.addKeyValue("totalSkills", totalSkills)
				.addKeyValue("totalDownloads", totalDownloads)
				.addKeyValue("newSkillsThisWeek", newSkillsThisWeek)
				.addKeyValue("topSkillsCount", topSkills.size())
				.log("概覽統計查詢完成");

		return new OverviewStats(totalSkills, totalDownloads, newSkillsThisWeek, topSkills);
	}

	/**
	 * 查詢下載數最高的 Skill 排行 — 簡單 ORDER BY + LIMIT，無需 aggregation pipeline。
	 *
	 * @param limit 回傳筆數上限
	 * @return 依下載數降冪排序的 {@link OverviewStats.TopSkill} 清單
	 */
	private List<OverviewStats.TopSkill> getTopSkills(int limit) {
		// S031: top skills 只回 PUBLISHED — SUSPENDED 不該出現在公開排行
		// S100a：加 author 欄位 enable frontend Link 至 canonical /skills/:author/:name
		return jdbc.query(
				"""
				SELECT name, author, download_count
				  FROM skills
				 WHERE status = 'PUBLISHED'
				 ORDER BY download_count DESC
				 LIMIT :limit
				""",
				new MapSqlParameterSource("limit", limit),
				(rs, rowNum) -> new OverviewStats.TopSkill(
						rs.getString("name"),
						rs.getString("author"),
						rs.getLong("download_count")));
	}

	/** 安全 COUNT(*) 查詢 — null 結果回傳 0。 */
	private long countOrZero(String sql, MapSqlParameterSource params) {
		Long n = jdbc.queryForObject(sql, params, Long.class);
		return n != null ? n : 0L;
	}

	/**
	 * S096d3 — 取得單一 skill 的每日下載趨勢（per-skill sparkline 資料源）。
	 *
	 * <p>回傳 {@code int[days]} 固定長度陣列，索引 0 = 最舊那天，索引 days-1 = 今天。
	 * 沒下載的天 fill 0；client 直接 map 為 SVG polyline points 不需 padding。
	 *
	 * <p>SQL 用 {@code date_trunc('day', ...)} bucket；server-side time zone 與 client
	 * 不同的 edge case 接受（dev/prod 皆 UTC; user-visible 時間 zone 漂移屬 known limitation）。
	 *
	 * @param skillId Skill aggregate ID
	 * @param days 統計區間天數（如 30 = 過去 30 天）
	 * @return 長度 days 的整數陣列；index 0 = days-1 天前，index days-1 = 今天
	 */
	public int[] getSkillDownloadTrend(String skillId, int days) {
		var since = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(days - 1, ChronoUnit.DAYS);
		var rows = jdbc.queryForList(
				"""
				SELECT date_trunc('day', downloaded_at)::date AS bucket_day,
				       COUNT(*) AS cnt
				  FROM download_events
				 WHERE skill_id = :skillId
				   AND downloaded_at >= :since
				 GROUP BY bucket_day
				 ORDER BY bucket_day
				""",
				new MapSqlParameterSource()
						.addValue("skillId", skillId)
						.addValue("since", java.sql.Timestamp.from(since)));

		var buckets = new int[days];
		var startEpochDay = since.atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay();
		for (var row : rows) {
			var bucketDate = ((java.sql.Date) row.get("bucket_day")).toLocalDate();
			int index = (int) (bucketDate.toEpochDay() - startEpochDay);
			if (index >= 0 && index < days) {
				buckets[index] = ((Number) row.get("cnt")).intValue();
			}
		}
		return buckets;
	}

	/**
	 * S096e1 — public aggregate stats for Landing page (per Engineering Handoff §2.1).
	 *
	 * <p>Public endpoint — 不需 auth；資料是平台 high-level 概觀 (no per-user PII)。
	 *
	 * @return platform 4-metric summary
	 */
	public PublicStats getPublicStats() {
		long totalSkills = countOrZero(
				"SELECT COUNT(*) FROM skills WHERE status = 'PUBLISHED'",
				new MapSqlParameterSource());
		var thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
		long downloads30d = countOrZero(
				"SELECT COUNT(*) FROM download_events WHERE downloaded_at >= :since",
				new MapSqlParameterSource("since", java.sql.Timestamp.from(thirtyDaysAgo)));
		long activePublishers = countOrZero(
				"SELECT COUNT(DISTINCT author) FROM skills WHERE status = 'PUBLISHED'",
				new MapSqlParameterSource());
		// auto-publish rate = LOW + NONE skills 比例（per S096c 4-tier；NONE 也是 auto-publish）
		long lowOrNone = countOrZero(
				"SELECT COUNT(*) FROM skills WHERE status = 'PUBLISHED' AND risk_level IN ('LOW', 'NONE')",
				new MapSqlParameterSource());
		int autoPublishPct = totalSkills > 0 ? (int) ((lowOrNone * 100L) / totalSkills) : 0;
		return new PublicStats(totalSkills, downloads30d, activePublishers, autoPublishPct);
	}

	/** Public Landing page stats payload. */
	public record PublicStats(long totalSkills, long downloads30d, long activePublishers, int autoPublishPct) {}

}
