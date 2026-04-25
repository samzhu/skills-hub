package io.github.samzhu.skillshub.skill.domain;

/**
 * 技能下載領域事件 — 當使用者成功取得技能套件下載連結時發布。
 *
 * <p>由 {@code SkillCommandService#downloadSkill} 產生。
 * 分析模組訂閱此事件以累計各版本的下載次數投影（{@code analytics} 模組）。
 *
 * @param aggregateId 技能聚合根的唯一識別碼（UUID）
 * @param version     被下載的語意化版本號（SemVer）
 */
public record SkillDownloadedEvent(
		String aggregateId,
		String version
) {}
