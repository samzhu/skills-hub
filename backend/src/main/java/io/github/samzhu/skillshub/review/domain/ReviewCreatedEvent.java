package io.github.samzhu.skillshub.review.domain;

import java.time.Instant;

/**
 * S098e2 — Review 建立領域事件。
 *
 * <p>由 {@link Review#create} aggregate factory 透過 {@code registerEvent} 註冊；
 * {@code ReviewRepository.save()} 經 Spring Data JDBC {@code @DomainEvents} proxy
 * 自動 publish 至 Modulith outbox（同 TX）。T02 SkillRatingProjectionListener
 * AFTER_COMMIT 訂閱以更新 {@code skills.average_rating} / {@code review_count}。
 */
public record ReviewCreatedEvent(
        String reviewId,
        String skillId,
        String authorId,
        int rating,
        String content,
        Instant createdAt
) {}
