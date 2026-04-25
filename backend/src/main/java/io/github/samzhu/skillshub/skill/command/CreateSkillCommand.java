package io.github.samzhu.skillshub.skill.command;

/**
 * 建立技能命令 — 攜帶初始化新技能所需的 metadata。
 *
 * <p>由 {@code SkillCommandController} 從 HTTP 請求反序列化後，
 * 傳入 {@code SkillCommandService#createSkill} 執行寫入操作。
 * 成功後產生 {@link io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent}。
 *
 * @param name        技能名稱（小寫連字號格式，最長 64 字元，符合 agentskills.io 規範）
 * @param description 技能功能描述（最長 1024 字元）
 * @param author      技能作者名稱
 * @param category    技能分類（如 DevOps、Testing）
 */
public record CreateSkillCommand(
		String name,
		String description,
		String author,
		String category
) {}
