package io.github.samzhu.skillshub.notification;

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
import io.github.samzhu.skillshub.community.events.RequestClaimedEvent;
import io.github.samzhu.skillshub.community.events.RequestFulfilledEvent;
import io.github.samzhu.skillshub.review.domain.ReviewCreatedEvent;
import io.github.samzhu.skillshub.security.SkillFlaggedEvent;

/**
 * S096h2-T02 — NotificationProjectionListener 跨模組 event subscription 測試。
 *
 * <p>{@code @SpringBootTest} full bootstrap（Modulith DIRECT_DEPENDENCIES 已驗在 S096g2-T01
 * 拉 transitive bean missing —{@code SkillAclController → StorageService} 等；full bootstrap
 * 換 reliability 是已驗 deviation）。Scenario API 等 AFTER_COMMIT async 完成後 verify projection。
 *
 * <p>涵蓋 AC：1（SkillFlagged → owner）/ 2（ReviewCreated → owner，skip 自我）/
 * 3（RequestClaimed → requester，skip 自我）/ 4（RequestFulfilled → requester）/
 * 5（preferences disabled → skip）/ 10（同 event redelivery → 1 row only）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios
class NotificationProjectionListenerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRepository notifRepo;

    @Autowired
    private NotificationPreferenceRepository prefRepo;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM request_votes");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: SkillFlagged → owner 收 flags 通知")
    void skillFlagged_ownerReceives(Scenario scenario) {
        var skillId = insertSkill("alice", "k8s autoscaler");

        scenario.publish(new SkillFlaggedEvent(skillId, "spam", "重複內容", "bob"))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L)
                .andVerify(c -> {
                    var rows = notifRepo.findByRecipient("alice", "flags", 10);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getTitle()).contains("k8s autoscaler", "spam");
                    assertThat(rows.get(0).getSkillId()).isEqualTo(skillId);
                    assertThat(rows.get(0).getRefEventId()).isEqualTo(skillId + ":spam");
                });
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: ReviewCreated → owner 收 reviews 通知；自我 review skip")
    void reviewCreated_ownerReceives_selfReviewSkipped(Scenario scenario) {
        var skillId = insertSkill("alice", "skill X");
        // bob 對 alice 的 skill 寫 review
        var reviewId = UUID.randomUUID().toString();

        scenario.publish(new ReviewCreatedEvent(reviewId, skillId, "bob", 5, "great", Instant.now()))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L)
                .andVerify(c -> {
                    var rows = notifRepo.findByRecipient("alice", "reviews", 10);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getTitle()).contains("bob", "skill X", "5★");
                    assertThat(rows.get(0).getRefEventId()).isEqualTo(reviewId);
                });

        // alice 對自己 skill 寫 review → 應 skip（不通知自己）
        var selfReviewId = UUID.randomUUID().toString();
        scenario.publish(new ReviewCreatedEvent(selfReviewId, skillId, "alice", 4, "self", Instant.now()))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L);
        // count 仍 1 — 自我 review 沒新增通知
        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(1L);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: RequestClaimed → requester 收 requests 通知；自我 claim skip")
    void requestClaimed_requesterReceives_selfClaimSkipped(Scenario scenario) {
        var requestId = insertRequest("alice", "我需要 k8s autoscaler");

        scenario.publish(new RequestClaimedEvent(requestId, "bob", Instant.now()))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L)
                .andVerify(c -> {
                    var rows = notifRepo.findByRecipient("alice", "requests", 10);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getTitle()).contains("bob", "我需要 k8s autoscaler");
                    assertThat(rows.get(0).getRefEventId()).isEqualTo(requestId + ":bob:claim");
                });

        // alice 自己 claim 自己 request → skip
        scenario.publish(new RequestClaimedEvent(requestId, "alice", Instant.now()))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L);
        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(1L);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: RequestFulfilled → requester 收 requests 通知 + skill name 入 title")
    void requestFulfilled_requesterReceives(Scenario scenario) {
        var requestId = insertRequest("alice", "需要 sql linter");
        var fulfilledSkillId = insertSkill("bob", "PostgreSQL Linter");

        scenario.publish(new RequestFulfilledEvent(requestId, "bob", fulfilledSkillId, Instant.now()))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L)
                .andVerify(c -> {
                    var rows = notifRepo.findByRecipient("alice", "requests", 10);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).getTitle())
                            .contains("需要 sql linter", "已完成", "PostgreSQL Linter");
                    assertThat(rows.get(0).getSkillId()).isEqualTo(fulfilledSkillId);
                    assertThat(rows.get(0).getRefEventId()).isEqualTo(requestId + ":fulfill");
                });
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: Preferences reviews_enabled=FALSE → 對應通知 skip；其他 categories 不影響")
    void preferencesDisabled_skipsTargetCategory(Scenario scenario) {
        var skillId = insertSkill("alice", "skill Y");
        // alice 關 reviews 通知
        var pref = NotificationPreference.defaults("alice");
        pref.update(null, false, null, null);
        prefRepo.save(pref);

        // ReviewCreated → 不應 INSERT
        var reviewId = UUID.randomUUID().toString();
        scenario.publish(new ReviewCreatedEvent(reviewId, skillId, "bob", 5, "great", Instant.now()))
                .andWaitAtMost(java.time.Duration.ofSeconds(2))
                .andWaitForStateChange(() -> "settled", x -> true);

        assertThat(notifRepo.countUnreadByRecipient("alice")).isZero();

        // 但 SkillFlagged 不被 reviews preference 影響 → 應 INSERT
        scenario.publish(new SkillFlaggedEvent(skillId, "spam", "x", "bob"))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L);
        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(1L);
    }

    @Test
    @Tag("AC-10")
    @DisplayName("AC-10: 同 event payload 二次 publish → UNIQUE 攔截 → 1 row only")
    void duplicateEvent_idempotent(Scenario scenario) {
        var skillId = insertSkill("alice", "skill Z");

        scenario.publish(new SkillFlaggedEvent(skillId, "malicious", "first", "bob"))
                .andWaitForStateChange(() -> notifRepo.countUnreadByRecipient("alice"), c -> c == 1L);

        // 二次 publish 同 (skillId, type) — listener save() 應 catch DuplicateKeyException
        scenario.publish(new SkillFlaggedEvent(skillId, "malicious", "second redelivery", "carol"))
                .andWaitAtMost(java.time.Duration.ofSeconds(2))
                .andWaitForStateChange(() -> "settled", x -> true);

        // 仍 1 row（idempotency 守住）
        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(1L);
    }

    private String insertSkill(String author, String name) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at)
                VALUES (?, ?, '測試 skill', ?, 'Test', 'PUBLISHED', 0, ?, ?)
                """,
                id, name, author,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private String insertRequest(String requesterId, String title) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO requests (id, title, description, requester_id, status, vote_count, created_at, updated_at, version)
                VALUES (?, ?, '測試 request', ?, 'OPEN', 0, ?, ?, 0)
                """,
                id, title, requesterId,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }
}
