package io.github.samzhu.skillshub.skill.security.events;

/**
 * S114a — Published by {@code SkillGrantService} after a grant row is deleted.
 *
 * <p>Consumed by {@code SkillAclProjectionListener} to rebuild
 * the denormalized {@code skills.acl_entries} JSONB array.
 *
 * @param skillId target skill UUID
 * @param grantId the deleted {@code skill_grants.id}
 */
public record SkillRevokedEvent(String skillId, String grantId) {}
