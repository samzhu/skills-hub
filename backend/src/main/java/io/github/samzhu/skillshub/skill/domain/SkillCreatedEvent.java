package io.github.samzhu.skillshub.skill.domain;

/**
 * 技能建立領域事件 — 當新技能完成初始化並持久化至 Event Store 時發布。
 *
 * <p>由 {@code SkillCommandService#createSkill} 產生，
 * 查詢側透過 {@code @ApplicationModuleListener} 消費此事件以建立 {@code SkillReadModel} 投影。
 *
 * @param aggregateId 技能聚合根的唯一識別碼（UUID）
 * @param name        平台顯示名稱（可和 SKILL.md package name 不同）
 * @param description 技能功能描述
 * @param author      技能作者名稱
 * @param category    技能分類（如 DevOps、Testing）
 */
public record SkillCreatedEvent(
		String aggregateId,
		String name,
		String description,
		String author,
		String category
) {}
