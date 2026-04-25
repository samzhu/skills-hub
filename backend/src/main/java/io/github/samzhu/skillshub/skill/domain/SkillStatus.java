package io.github.samzhu.skillshub.skill.domain;

/**
 * 技能的生命週期狀態。
 *
 * <ul>
 *   <li>{@link #DRAFT} — 草稿，尚未公開，僅建立者可見</li>
 *   <li>{@link #PUBLISHED} — 已發布，可被搜尋與下載</li>
 *   <li>{@link #SUSPENDED} — 已停用，因安全風險或違規而下架，不可下載</li>
 * </ul>
 */
public enum SkillStatus {
	/** 草稿狀態：技能已建立但尚未發布任何版本。 */
	DRAFT,
	/** 發布狀態：至少有一個版本通過安全評估並對外公開。 */
	PUBLISHED,
	/** 停用狀態：技能因安全評估結果或管理員決策而暫時或永久下架。 */
	SUSPENDED
}
