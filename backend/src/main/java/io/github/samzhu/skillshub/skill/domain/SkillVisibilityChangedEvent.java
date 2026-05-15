package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;

/**
 * S177 — public visibility changed through public grant create/revoke.
 */
public record SkillVisibilityChangedEvent(
        String aggregateId,
        boolean isPublic,
        String grantId,
        String changedBy,
        Instant changedAt) {
}
