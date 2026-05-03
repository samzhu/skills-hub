package io.github.samzhu.skillshub.review.domain;

import java.time.Instant;

/**
 * S098e2 — Review 刪除領域事件。Listener AFTER_COMMIT 訂閱以重算 averageRating（T02）。
 */
public record ReviewDeletedEvent(
        String reviewId,
        String skillId,
        String authorId,
        Instant deletedAt
) {}
