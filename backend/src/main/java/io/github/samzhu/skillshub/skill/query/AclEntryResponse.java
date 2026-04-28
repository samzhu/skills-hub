package io.github.samzhu.skillshub.skill.query;

/**
 * S016：ACL entry 對外 JSON 表示 — colon-separated 字串拆解後的三段 tuple。
 *
 * <p>由 {@link SkillAclQueryService} 從 {@code skills.acl_entries} 字串元素解析產生，
 * 由 {@code SkillAclController.list} 序列化為 JSON 回傳。
 *
 * @param type       命名空間（{@code user} / {@code role} / {@code group}）
 * @param principal  命名空間下識別字串
 * @param permission 授權動詞
 */
public record AclEntryResponse(String type, String principal, String permission) {}
