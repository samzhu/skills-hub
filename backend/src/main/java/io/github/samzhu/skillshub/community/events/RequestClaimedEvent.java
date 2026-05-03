package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/** S096g2 AC-7 — Request 認領 OPEN→IN_PROGRESS。 */
public record RequestClaimedEvent(
        String requestId,
        String claimerId,
        Instant claimedAt
) {}
