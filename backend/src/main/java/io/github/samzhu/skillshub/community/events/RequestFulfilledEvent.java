package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/** S096g2 AC-10 — Request 完成 IN_PROGRESS→FULFILLED + 綁定 PUBLISHED skill。 */
public record RequestFulfilledEvent(
        String requestId,
        String claimerId,
        String fulfilledSkillId,
        Instant fulfilledAt
) {}
