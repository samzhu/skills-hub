package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/** S096g2 AC-9 — Request 釋放認領 IN_PROGRESS→OPEN。 */
public record RequestReleasedEvent(
        String requestId,
        String releasedBy,
        Instant releasedAt
) {}
