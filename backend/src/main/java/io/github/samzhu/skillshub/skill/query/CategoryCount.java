package io.github.samzhu.skillshub.skill.query;

/**
 * 分類統計投影 — 表示某技能分類下的技能數量，用於首頁分類瀏覽與統計 API。
 *
 * <p>由 {@code SkillQueryService} 從 {@link SkillReadModelRepository}
 * 以聚合查詢產生，不對應任何 Firestore 集合。
 *
 * @param name  分類名稱（如 DevOps、Testing）
 * @param count 該分類下已發布技能的數量
 */
public record CategoryCount(
		String name,
		long count
) {}
