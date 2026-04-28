package io.github.samzhu.skillshub.skill.domain;

/**
 * ACL 撤銷領域事件 — 為某 Skill aggregate 移除一條 acl_entries pattern。
 *
 * <p>由 {@code Skill#revokeAcl(RevokeAclCommand)} 在 aggregate 確認該 entry 確實存在後產生，
 * 經 {@code SkillCommandService#revokeAcl} 持久化並發布。
 *
 * <p>查詢側 {@code SkillProjection.on(SkillAclRevokedEvent)} 訂閱此事件以從
 * {@code skills.acl_entries} 移除對應字串。
 *
 * @param aggregateId Skill 聚合根 UUID
 * @param type        principal 命名空間
 * @param principal   命名空間下的識別字串
 * @param permission  授權動詞
 * @param revokedBy   執行 revoke 的 user-id（從 SecurityContext 取得）
 */
public record SkillAclRevokedEvent(
        String aggregateId,
        String type,
        String principal,
        String permission,
        String revokedBy
) {}
