package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/**
 * S096g2 AC-5/6 — Request vote 切換事件（toggle on/off）。
 *
 * <p>由 T02 RequestVoteService 直接 publish via ApplicationEventPublisher（vote
 * 走 atomic SQL，不經 aggregate save outbox path；對齊 S076 download_count 同 pattern）。
 */
public record RequestVotedEvent(
        String requestId,
        String userId,
        boolean voted,
        long voteCount,
        Instant votedAt
) {}
