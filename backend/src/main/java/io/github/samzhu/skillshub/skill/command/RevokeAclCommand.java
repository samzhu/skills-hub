package io.github.samzhu.skillshub.skill.command;

/**
 * Revoke ACL entry 命令 — 攜帶將 type:principal:permission pattern 從某 Skill ACL 移除所需資訊。
 *
 * <p>由 {@code SkillAclController#revoke} 從 HTTP 請求 query params + {@code CurrentUserProvider}
 * 組合產生。aggregate 端會驗證該 entry 確實存在；成功則產生
 * {@link io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent}。
 *
 * @param skillId    目標 Skill 聚合根 UUID
 * @param type       principal 命名空間
 * @param principal  命名空間下識別字串
 * @param permission 授權動詞
 * @param revokedBy  執行撤銷的 user-id（從 SecurityContext 取得）
 */
public record RevokeAclCommand(
        String skillId,
        String type,
        String principal,
        String permission,
        String revokedBy
) {}
