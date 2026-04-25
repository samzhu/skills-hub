package io.github.samzhu.skillshub.analytics;

import java.util.List;

/**
 * 平台整體概覽統計資料，由 {@link AnalyticsService#getOverview()} 彙整後回傳。
 *
 * @param totalSkills      平台目前上架的 Skill 總數
 * @param totalDownloads   所有 Skill 的累計下載次數
 * @param newSkillsThisWeek 近 7 天新上架的 Skill 數量
 * @param topSkills        下載數最高的 Skill 排行清單（預設前 10 名）
 */
public record OverviewStats(
		long totalSkills,
		long totalDownloads,
		long newSkillsThisWeek,
		List<TopSkill> topSkills
) {
	/**
	 * 熱門 Skill 排行項目。
	 *
	 * @param name      Skill 名稱
	 * @param downloads 累計下載次數
	 */
	public record TopSkill(String name, long downloads) {}
}
