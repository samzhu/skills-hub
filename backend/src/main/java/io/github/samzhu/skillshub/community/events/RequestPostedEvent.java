package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/** S096g2 AC-1 — Request 建立。由 Request.create() registerEvent；repo.save() 透過 outbox publish。 */
public record RequestPostedEvent(
        String requestId,
        String title,
        String requesterId,
        Instant postedAt
) {}
