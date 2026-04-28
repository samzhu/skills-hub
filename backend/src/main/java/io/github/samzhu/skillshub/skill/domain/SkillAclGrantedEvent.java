package io.github.samzhu.skillshub.skill.domain;

/**
 * ACL 授權領域事件 — 為某 Skill aggregate 新增一條 acl_entries pattern。
 *
 * <p>由 {@code Skill#grantAcl(GrantAclCommand)} 在 aggregate 通過業務不變量檢查
 * （無重複 grant）後產生，經 {@code SkillCommandService#grantAcl} 持久化至
 * {@code domain_events} 並透過 {@code ApplicationEventPublisher} 發布。
 *
 * <p>查詢側 {@code SkillProjection.on(SkillAclGrantedEvent)} 訂閱此事件以將
 * {@code "<type>:<principal>:<permission>"} 字串 append 至 {@code skills.acl_entries}。
 *
 * @param aggregateId Skill 聚合根 UUID
 * @param type        principal 命名空間，MVP 限 {@code "user"} / {@code "role"} / {@code "group"}
 * @param principal   命名空間下的識別字串（如 user-id、role 名稱、group 名稱）
 * @param permission  授權動詞，MVP 限 {@code "read"} / {@code "write"} / {@code "delete"} /
 *                    {@code "suspend"} / {@code "reactivate"}
 * @param grantedBy   執行 grant 的 user-id（從 SecurityContext 取得，非由 client 提供避免 spoof）
 */
public record SkillAclGrantedEvent(
        String aggregateId,
        String type,
        String principal,
        String permission,
        String grantedBy
) {}
