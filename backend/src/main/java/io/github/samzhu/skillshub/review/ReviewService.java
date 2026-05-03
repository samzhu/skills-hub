package io.github.samzhu.skillshub.review;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.review.domain.Review;
import io.github.samzhu.skillshub.review.domain.ReviewDeletedEvent;
import io.github.samzhu.skillshub.review.domain.ReviewRepository;
import io.github.samzhu.skillshub.shared.api.ReviewForbiddenException;

/**
 * S098e2 — Review 應用服務（3-line orchestration per ADR-002 canonical pattern）。
 *
 * <p>Create / delete 走 aggregate 充血方法 + repo.save() 觸發 outbox publish；
 * read 直接呼叫 repository derived query（無 projection 中介）。
 */
@Service
public class ReviewService {

    private final ReviewRepository repo;
    private final ApplicationEventPublisher events;

    public ReviewService(ReviewRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    /**
     * AC-1 / AC-2 / AC-3 / AC-4 — 建立新 review。
     *
     * @throws IllegalArgumentException AC-2 rating range / AC-3 content too long
     * @throws IllegalStateException    AC-4 已對該 skill 寫過 review（review_already_exists）
     */
    @Transactional
    public String createReview(String skillId, String authorId, int rating, String content) {
        // AC-4：1-per-user — 雙保險（DB UNIQUE constraint 是 race-safe 終局，application-level
        // 預檢給 user-friendly 409 message）
        if (repo.existsBySkillIdAndAuthorId(skillId, authorId)) {
            throw new IllegalStateException("review_already_exists: user " + authorId
                    + " already reviewed skill " + skillId);
        }
        // Aggregate factory 內含 AC-2 / AC-3 validation
        var review = Review.create(skillId, authorId, rating, content);
        return repo.save(review).getId();
    }

    /**
     * AC-6 / AC-7 — 刪除自己 review。
     *
     * @throws NoSuchElementException     review id 不存在
     * @throws io.github.samzhu.skillshub.shared.api.ReviewForbiddenException
     *         AC-7 requester 非原作者
     */
    @Transactional
    public void deleteReview(String reviewId, String requesterId) {
        var review = repo.findById(reviewId)
                .orElseThrow(() -> new NoSuchElementException("Review not found: " + reviewId));
        // AC-7：non-author 拒絕 — 在 service 層守門（不走 aggregate registerEvent path 因為
        // delete 不適合用 save() 觸發 outbox：load 後 isNew=true 會誤觸 INSERT 衝主鍵；
        // 改用 ApplicationEventPublisher 直接發 event，per FlagService 既有 pattern）
        if (!review.getAuthorId().equals(requesterId)) {
            throw new ReviewForbiddenException("not_review_author");
        }
        repo.deleteById(reviewId);
        events.publishEvent(new ReviewDeletedEvent(reviewId, review.getSkillId(),
                review.getAuthorId(), Instant.now()));
    }

    /** AC-8 — 列表 endpoint，後端 ORDER BY desc 由 derived query 提供。 */
    public List<Review> getReviewsBySkill(String skillId) {
        return repo.findBySkillIdOrderByCreatedAtDesc(skillId);
    }
}
