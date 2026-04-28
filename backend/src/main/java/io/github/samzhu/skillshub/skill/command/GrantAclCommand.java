package io.github.samzhu.skillshub.skill.command;

/**
 * Grant ACL entry 命令 — 攜帶將 type:principal:permission pattern 加入某 Skill ACL 所需資訊。
 *
 * <p>由 {@code SkillAclController#grant} 從 HTTP 請求 + {@code CurrentUserProvider}
 * 組合產生，傳入 {@code SkillCommandService#grantAcl}。aggregate 端會驗證
 * 無重複 grant；成功則產生 {@link io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent}。
 *
 * @param skillId    目標 Skill 聚合根 UUID
 * @param type       principal 命名空間（{@code user} / {@code role} / {@code group}）
 * @param principal  命名空間下識別字串
 * @param permission 授權動詞（{@code read} / {@code write} / {@code delete} /
 *                   {@code suspend} / {@code reactivate}）
 * @param grantedBy  執行授權的 user-id（從 SecurityContext 取得）
 */
public record GrantAclCommand(
        String skillId,
        String type,
        String principal,
        String permission,
        String grantedBy
) {}
