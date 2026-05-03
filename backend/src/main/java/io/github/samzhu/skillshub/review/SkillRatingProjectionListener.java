package io.github.samzhu.skillshub.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.review.domain.ReviewCreatedEvent;
import io.github.samzhu.skillshub.review.domain.ReviewDeletedEvent;
import io.github.samzhu.skillshub.skill.query.SkillRatingService;

/**
 * S098e2-T02 — AFTER_COMMIT 訂閱 ReviewCreated/ReviewDeleted events，
 * 觸發 {@link SkillRatingService#refresh} 重算 skills.average_rating / review_count。
 *
 * <p>放置位置決策（per spec §2.1）：放 review module 而非 skill module —
 * {@code review → skill::query} 單向依賴對齊既有 ScanOrchestrator
 * （security → skill::query）pattern；避免 skill 模組反向訂閱 review
 * events 引入週期。Modulith verifier 接受（review allowedDependencies
 * 顯式宣告 {@code skill :: query}）。
 *
 * <p>{@code @ApplicationModuleListener} 是 Spring Modulith 提供的
 * AFTER_COMMIT + async + 自動 outbox-backed 組合 annotation；listener
 * 失敗時 event 留 outbox 重試（at-least-once）。
 *
 * <p>Idempotency：{@code SkillRatingService.refresh} 每次重算 AVG/COUNT，
 * 與重複呼叫等效；at-least-once 重試安全。
 */
@Component
class SkillRatingProjectionListener {

    private static final Logger log = LoggerFactory.getLogger(SkillRatingProjectionListener.class);

    private final SkillRatingService ratingService;

    SkillRatingProjectionListener(SkillRatingService ratingService) {
        this.ratingService = ratingService;
    }

    @ApplicationModuleListener
    void onReviewCreated(ReviewCreatedEvent event) {
        log.atDebug()
                .addKeyValue("skillId", event.skillId())
                .addKeyValue("reviewId", event.reviewId())
                .log("Refreshing skill rating projection after review created");
        ratingService.refresh(event.skillId());
    }

    @ApplicationModuleListener
    void onReviewDeleted(ReviewDeletedEvent event) {
        log.atDebug()
                .addKeyValue("skillId", event.skillId())
                .addKeyValue("reviewId", event.reviewId())
                .log("Refreshing skill rating projection after review deleted");
        ratingService.refresh(event.skillId());
    }
}
