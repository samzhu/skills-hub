package io.github.samzhu.skillshub.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.review.domain.ReviewCreatedEvent;
import io.github.samzhu.skillshub.review.domain.ReviewDeletedEvent;

/**
 * S098e2-T02 AC-5 — SkillRatingProjectionListener 訂閱 ReviewCreated/Deleted events
 * 觸發 SkillRatingService.refresh，更新 skills.average_rating + review_count。
 *
 * <p>{@code @ApplicationModuleTest(DIRECT_DEPENDENCIES)} bootstrap 只載 review 模組
 * 直接依賴（shared::events/api/security + skill::domain/query），對齊 AuditEventListenerTest
 * 既有 pattern。Scenario API 等 listener AFTER_COMMIT 完成後 verify projection state。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios
class SkillRatingProjectionListenerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: ReviewCreatedEvent 觸發 projection — average_rating + review_count 同步更新")
    void reviewCreated_updatesProjection(Scenario scenario) {
        var skillId = insertSkill("alice");
        // 種 3 則 review (5/4/3)
        insertReview(skillId, "u1", 5);
        insertReview(skillId, "u2", 4);
        insertReview(skillId, "u3", 3);
        // 第 4 則由 listener 透過事件觸發 refresh — 先 INSERT 第 4 筆 (rating=2)，
        // 然後 publish event 模擬 ReviewService 已寫 DB 後 outbox publish 路徑
        var reviewId = insertReview(skillId, "u4", 2);

        scenario.publish(new ReviewCreatedEvent(
                reviewId, skillId, "u4", 2, "ok", Instant.now()))
                .andWaitForStateChange(() -> readReviewCount(skillId), count -> count == 4L)
                .andVerify(count -> {
                    assertThat(readAverageRating(skillId)).isEqualByComparingTo("3.50");
                    assertThat(count).isEqualTo(4L);
                });
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: ReviewDeletedEvent 觸發 projection — review_count 減 1，AVG 重算")
    void reviewDeleted_updatesProjection(Scenario scenario) {
        var skillId = insertSkill("alice");
        // 種 3 則 review (5/4/3)；先正常 refresh 至 avg=4.0 / count=3
        insertReview(skillId, "u1", 5);
        var deletedReviewId = insertReview(skillId, "u2", 4);
        insertReview(skillId, "u3", 3);
        // Mimic 已執行過 refresh — 直接寫 projection 同步初值
        jdbc.update("UPDATE skills SET average_rating = 4.00, review_count = 3 WHERE id = ?", skillId);

        // 模擬 ReviewService.deleteReview：先刪 row，再 publish event；listener
        // 重算 AVG/COUNT 應從 5/3 兩 row 算出 avg=4.00
        jdbc.update("DELETE FROM reviews WHERE id = ?", deletedReviewId);

        scenario.publish(new ReviewDeletedEvent(
                deletedReviewId, skillId, "u2", Instant.now()))
                .andWaitForStateChange(() -> readReviewCount(skillId), count -> count == 2L)
                .andVerify(count -> {
                    assertThat(readAverageRating(skillId)).isEqualByComparingTo("4.00");
                    assertThat(count).isEqualTo(2L);
                });
    }

    private String insertSkill(String author) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, '測試 skill', ?, 'test', 'PUBLISHED', 0, ?, ?, ?)
                """,
                id, "skill-" + id.substring(0, 8), author,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), author);
        return id;
    }

    private String insertReview(String skillId, String authorId, int rating) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reviews (id, skill_id, author_id, rating, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, '測試 review', ?, ?)
                """,
                id, skillId, authorId, rating,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private Long readReviewCount(String skillId) {
        var result = jdbc.queryForObject(
                "SELECT review_count FROM skills WHERE id = ?", Long.class, skillId);
        return result;
    }

    private java.math.BigDecimal readAverageRating(String skillId) {
        return jdbc.queryForObject(
                "SELECT average_rating FROM skills WHERE id = ?",
                java.math.BigDecimal.class, skillId);
    }
}
