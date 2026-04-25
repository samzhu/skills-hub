package io.github.samzhu.skillshub.security;

/**
 * 領域事件：Skill 被舉報（Flag）時發布。
 * <p>
 * 由 {@link FlagService#createFlag} 在持久化 Flag 後透過
 * {@code ApplicationEventPublisher} 發布，供下游模組（如通知、審核佇列）訂閱。
 * </p>
 *
 * @param aggregateId 被舉報的 Skill 識別碼
 * @param type        舉報類型，例如 {@code MALICIOUS_CODE}、{@code POLICY_VIOLATION}
 * @param description 舉報原因說明
 * @param reportedBy  舉報人識別碼；MVP 階段固定為 {@code "anonymous"}
 */
public record SkillFlaggedEvent(
		String aggregateId,
		String type,
		String description,
		String reportedBy
) {}
