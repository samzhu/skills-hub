package io.github.samzhu.skillshub.skill.security.events;

/**
 * S114a — Published by {@code SkillGrantService} after a new grant row is persisted.
 *
 * <p>Consumed by {@code SkillAclProjectionListener} to rebuild
 * the denormalized {@code skills.acl_entries} JSONB array.
 *
 * @param skillId target skill UUID
 * @param grantId newly created {@code skill_grants.id}
 */
public record SkillGrantedEvent(String skillId, String grantId) {}
