package io.github.samzhu.skillshub.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.github.samzhu.skillshub.review.domain.Review;
import io.github.samzhu.skillshub.shared.api.PlainTextDeserializer;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.UserDisplay;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;

/**
 * S098e2 — Review REST endpoints。
 *
 * <p>合併 command + query controller 為單檔（trim from spec template）— T01 scope 內
 * 3 個 endpoint volume 不需拆分。
 *
 * <ul>
 *   <li>{@code POST /api/v1/skills/{skillId}/reviews} — 建立（AC-1～4/9）</li>
 *   <li>{@code DELETE /api/v1/skills/{skillId}/reviews/{reviewId}} — 刪自己 review（AC-6/7）</li>
 *   <li>{@code GET /api/v1/skills/{skillId}/reviews} — 列表 time desc（AC-8）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/reviews")
class ReviewController {

    private final ReviewService reviewService;
    private final CurrentUserProvider users;
    private final UserDisplayService userDisplayService;

    ReviewController(ReviewService reviewService, CurrentUserProvider users, UserDisplayService userDisplayService) {
        this.reviewService = reviewService;
        this.users = users;
        this.userDisplayService = userDisplayService;
    }

    @PostMapping
    ResponseEntity<Map<String, String>> create(
            @PathVariable String skillId,
            @RequestBody CreateReviewRequest req) {
        var authorId = users.current().userId();
        var id = reviewService.createReview(skillId, authorId, req.rating(), req.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/{reviewId}")
    ResponseEntity<Void> delete(
            @PathVariable String skillId,
            @PathVariable String reviewId) {
        var requesterId = users.current().userId();
        reviewService.deleteReview(reviewId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    List<ReviewResponse> list(@PathVariable String skillId) {
        var reviews = reviewService.getReviewsBySkill(skillId);
        var authorDisplays = userDisplayService.resolveAll(reviews.stream()
                .map(Review::getAuthorId)
                .toList(), false);

        return reviews.stream()
                .map(review -> ReviewResponse.from(review, authorDisplays.get(review.getAuthorId())))
                .toList();
    }

    /**
     * Request body for POST endpoint.
     *
     * <p>S161：{@code content} 走 {@link PlainTextDeserializer} silently strip HTML markup
     * （防 stored XSS — 即使前端某天改用 react-markdown + rehypeRaw 也擋住）。
     */
    record CreateReviewRequest(
            int rating,
            @JsonDeserialize(using = PlainTextDeserializer.class) String content) {}

    /** Response DTO — 不直接洩漏 aggregate 內部 mutation method。 */
    record ReviewResponse(
            String id,
            String skillId,
            String authorId,
            String authorDisplayName,
            String authorHandle,
            int rating,
            String content,
            Instant createdAt,
            Instant updatedAt
    ) {
        static ReviewResponse from(Review r, UserDisplay authorDisplay) {
            return new ReviewResponse(r.getId(), r.getSkillId(), r.getAuthorId(),
                    authorDisplay == null ? null : authorDisplay.displayName(),
                    authorDisplay == null ? null : authorDisplay.handle(),
                    r.getRating(), r.getContent(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
