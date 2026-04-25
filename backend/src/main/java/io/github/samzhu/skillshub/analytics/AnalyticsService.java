package io.github.samzhu.skillshub.analytics;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * 分析（Analytics）應用服務，負責彙整平台統計資料並回傳概覽資訊。
 * <p>
 * 資料來源為 Firestore 中的 {@code skills} 與 {@code download_events} Collection，
 * 均屬於 CQRS 讀取側的 Projection，不直接操作領域聚合。
 * </p>
 */
@Service
public class AnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final MongoTemplate mongoTemplate;

	public AnalyticsService(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	/**
	 * 取得平台整體概覽統計，包含 Skill 總數、下載總數、本週新增數量及下載排行榜。
	 *
	 * @return 包含各項統計指標的 {@link OverviewStats}
	 */
	public OverviewStats getOverview() {
		long totalSkills = mongoTemplate.count(new Query(), "skills");
		long totalDownloads = mongoTemplate.count(new Query(), "download_events");

		var oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
		long newSkillsThisWeek = mongoTemplate.count(
				Query.query(Criteria.where("createdAt").gte(oneWeekAgo)), "skills");

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
	 * 使用 MongoDB Aggregation Pipeline 查詢下載數最高的 Skill 排行。
	 *
	 * @param limit 回傳筆數上限
	 * @return 依下載數降冪排序的 {@link OverviewStats.TopSkill} 清單
	 */
	private List<OverviewStats.TopSkill> getTopSkills(int limit) {
		var agg = Aggregation.newAggregation(
				Aggregation.sort(Sort.Direction.DESC, "downloadCount"),
				Aggregation.limit(limit),
				Aggregation.project("name", "downloadCount")
		);
		var results = mongoTemplate.aggregate(agg, "skills", org.bson.Document.class).getMappedResults();
		return results.stream()
				.map(doc -> new OverviewStats.TopSkill(
						doc.getString("name"),
						doc.get("downloadCount", Number.class).longValue()))
				.toList();
	}

}
