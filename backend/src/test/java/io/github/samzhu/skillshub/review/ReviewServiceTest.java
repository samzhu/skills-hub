package io.github.samzhu.skillshub.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.review.domain.ReviewRepository;
import io.github.samzhu.skillshub.shared.api.ReviewForbiddenException;

/**
 * S098e2-T01 — ReviewService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>涵蓋 AC：
 * <ul>
 *   <li>AC-1：建立 review 成功路徑</li>
 *   <li>AC-2：rating out-of-range 拒絕</li>
 *   <li>AC-3：content 長度上限</li>
 *   <li>AC-4：每 user 每 skill 1 則</li>
 *   <li>AC-6：刪除自己 review</li>
 *   <li>AC-7：刪別人 review 拒絕</li>
 *   <li>AC-8：列表 endpoint 時序 desc</li>
 * </ul>
 *
 * <p>AC-5 projection 更新由 T02 SkillRatingProjectionListenerTest 涵蓋；AC-9 (auth)
 * 由現行 Spring Security MVP permit-all 暫無強制 401，留待 security 階段重審。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewServiceTest {

    @Autowired
    private ReviewService service;

    @Autowired
    private ReviewRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        // FK ON DELETE CASCADE：刪 skills 連帶 reviews；先刪細表保險
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: 建立 review 成功路徑")
    void createReview_happyPath() {
        var skillId = insertSkill("alice");

        var reviewId = service.createReview(skillId, "alice", 5, "Great");

        assertThat(reviewId).isNotBlank();
        var saved = repo.findById(reviewId).orElseThrow();
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getContent()).isEqualTo("Great");
        assertThat(saved.getAuthorId()).isEqualTo("alice");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: rating out-of-range 拒絕（0 或 6）")
    void createReview_ratingOutOfRange_rejected() {
        var skillId = insertSkill("alice");

        assertThatThrownBy(() -> service.createReview(skillId, "alice", 0, "Bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating_out_of_range");

        assertThatThrownBy(() -> service.createReview(skillId, "bob", 6, "Bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rating_out_of_range");

        assertThat(repo.findBySkillIdOrderByCreatedAtDesc(skillId)).isEmpty();
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: content 超 2000 字元拒絕")
    void createReview_contentTooLong_rejected() {
        var skillId = insertSkill("alice");
        var longContent = "x".repeat(2001);

        assertThatThrownBy(() -> service.createReview(skillId, "alice", 5, longContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content_too_long");

        assertThat(repo.findBySkillIdOrderByCreatedAtDesc(skillId)).isEmpty();
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: 每 user 每 skill 1 則 — 重複 POST 拒絕 409")
    void createReview_duplicateAuthor_rejected() {
        var skillId = insertSkill("alice");
        service.createReview(skillId, "alice", 5, "first");

        assertThatThrownBy(() -> service.createReview(skillId, "alice", 4, "second"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review_already_exists");

        assertThat(repo.findBySkillIdOrderByCreatedAtDesc(skillId)).hasSize(1);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: 作者刪自己 review 成功")
    void deleteReview_byAuthor_success() {
        var skillId = insertSkill("alice");
        var reviewId = service.createReview(skillId, "alice", 5, "Great");

        service.deleteReview(reviewId, "alice");

        assertThat(repo.findById(reviewId)).isEmpty();
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: 非作者刪 review 拒絕 403 not_review_author")
    void deleteReview_byNonAuthor_rejected() {
        var skillId = insertSkill("alice");
        var reviewId = service.createReview(skillId, "alice", 5, "Great");

        assertThatThrownBy(() -> service.deleteReview(reviewId, "bob"))
                .isInstanceOf(ReviewForbiddenException.class)
                .hasMessageContaining("not_review_author");

        // 仍存在
        assertThat(repo.findById(reviewId)).isPresent();
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-6 negative: 刪不存在 review 拋 404 NoSuchElementException")
    void deleteReview_notFound_throws() {
        assertThatThrownBy(() -> service.deleteReview("non-existent-uuid", "alice"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: getReviewsBySkill 時序 desc，最新在前")
    void getReviewsBySkill_orderedDesc() throws InterruptedException {
        var skillId = insertSkill("alice");
        service.createReview(skillId, "u1", 5, "first");
        Thread.sleep(10); // 確保 createdAt 嚴格遞增
        service.createReview(skillId, "u2", 4, "second");
        Thread.sleep(10);
        service.createReview(skillId, "u3", 3, "third");

        var reviews = service.getReviewsBySkill(skillId);

        assertThat(reviews).hasSize(3);
        assertThat(reviews.get(0).getContent()).isEqualTo("third");
        assertThat(reviews.get(1).getContent()).isEqualTo("second");
        assertThat(reviews.get(2).getContent()).isEqualTo("first");
        // createdAt 應嚴格 desc
        assertThat(reviews.get(0).getCreatedAt()).isAfter(reviews.get(1).getCreatedAt());
        assertThat(reviews.get(1).getCreatedAt()).isAfter(reviews.get(2).getCreatedAt());
    }

    /** Test fixture：種一個 PUBLISHED skill row，回傳其 id。 */
    private String insertSkill(String author) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, '測試 skill', ?, 'test', 'PUBLISHED', 0, ?, ?, ?)
                """,
                id,
                "skill-" + id.substring(0, 8),
                author,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), author);
        return id;
    }
}
