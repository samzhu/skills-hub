package io.github.samzhu.skillshub.skill.command;

/**
 * S018：Reactivate Skill 命令 — 將 SUSPENDED skill 重啟回 PUBLISHED 狀態。
 *
 * <p>由 {@code SkillCommandController#reactivate} 從 HTTP 請求產生。aggregate 端驗
 * state machine 不變量（只 SUSPENDED → PUBLISHED 合法）；成功則產生
 * {@link io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent}。
 *
 * @param skillId  目標 Skill 聚合根 UUID
 * @param reason   重啟理由
 */
public record ReactivateCommand(
        String skillId,
        String reason
) {}
