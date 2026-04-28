package io.github.samzhu.skillshub.skill.command;

/**
 * S018：Suspend Skill 命令 — 攜帶停用某 Skill 所需資訊。
 *
 * <p>由 {@code SkillCommandController#suspend} 從 HTTP 請求 + {@code CurrentUserProvider}
 * 組合產生，傳入 {@code SkillCommandService#suspend}。aggregate 端會驗 state machine 不變量
 * （只 PUBLISHED → SUSPENDED 合法）；成功則產生
 * {@link io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent}。
 *
 * @param skillId      目標 Skill 聚合根 UUID
 * @param reason       停用理由
 * @param suspendedBy  執行 suspend 的 user-id（從 SecurityContext 取得）
 */
public record SuspendCommand(
        String skillId,
        String reason,
        String suspendedBy
) {}
