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
		long totalSkills = countOrZero("SELECT COUNT(*) FROM skills", new MapSqlParameterSource());
		long totalDownloads = countOrZero("SELECT COUNT(*) FROM download_events", new MapSqlParameterSource());

		var oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
		long newSkillsThisWeek = countOrZero(
				"SELECT COUNT(*) FROM skills WHERE created_at >= :since",
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
		return jdbc.query(
				"""
				SELECT name, download_count
				  FROM skills
				 ORDER BY download_count DESC
				 LIMIT :limit
				""",
				new MapSqlParameterSource("limit", limit),
				(rs, rowNum) -> new OverviewStats.TopSkill(
						rs.getString("name"),
						rs.getLong("download_count")));
	}

	/** 安全 COUNT(*) 查詢 — null 結果回傳 0。 */
	private long countOrZero(String sql, MapSqlParameterSource params) {
		Long n = jdbc.queryForObject(sql, params, Long.class);
		return n != null ? n : 0L;
	}

}
