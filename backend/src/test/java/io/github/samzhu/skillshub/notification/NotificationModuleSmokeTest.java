package io.github.samzhu.skillshub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S096h2-T01 — Notification + NotificationPreference 基礎建設 smoke test。
 *
 * <p>本 task scope 僅驗證 schema/aggregate/repo 三層 round-trip + UNIQUE constraint 守則；
 * cross-module listener wiring 由 T02 NotificationProjectionListenerTest 涵蓋；mutation
 * 路徑（mark-read / delete / preferences update）由 T03 NotificationServiceTest 涵蓋。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationModuleSmokeTest {

    @Autowired
    private NotificationRepository notifRepo;

    @Autowired
    private NotificationPreferenceRepository prefRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
    }

    @Test
    @DisplayName("Notification.create + save → INSERT + findById round-trip")
    void notification_create_persist_roundTrip() {
        var n = Notification.create("alice", "flags", "你的 skill X 被回報",
                "spam type", "sk-1", "evt-1");
        var saved = notifRepo.save(n);

        var loaded = notifRepo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getRecipientId()).isEqualTo("alice");
        assertThat(loaded.getCategory()).isEqualTo("flags");
        assertThat(loaded.getTitle()).isEqualTo("你的 skill X 被回報");
        assertThat(loaded.getRefEventId()).isEqualTo("evt-1");
        assertThat(loaded.isRead()).isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Notification.markRead + save → UPDATE read_at non-null（@Version isNew=false path）")
    void notification_markRead_update() {
        var n = notifRepo.save(Notification.create("alice", "reviews", "新評論",
                null, "sk-1", "evt-2"));
        var loaded = notifRepo.findById(n.getId()).orElseThrow();

        loaded.markRead();
        notifRepo.save(loaded);

        var reread = notifRepo.findById(n.getId()).orElseThrow();
        assertThat(reread.isRead()).isTrue();
        assertThat(reread.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("Notification.isOwnedBy 守 ownership（recipient 比對）")
    void notification_isOwnedBy() {
        var n = Notification.create("alice", "requests", "認領通知", null, null, "evt-3");
        assertThat(n.isOwnedBy("alice")).isTrue();
        assertThat(n.isOwnedBy("bob")).isFalse();
    }

    @Test
    @DisplayName("UNIQUE(recipient_id, ref_event_id, category) — 同 event 二次 INSERT → DuplicateKeyException")
    void notification_uniqueConstraint_blocksDuplicate() {
        var first = Notification.create("alice", "flags", "通知1", null, "sk-1", "evt-99");
        notifRepo.save(first);

        var dup = Notification.create("alice", "flags", "通知2 同 ref/cat", null, "sk-1", "evt-99");
        assertThatThrownBy(() -> notifRepo.save(dup))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("UNIQUE 不阻 cross-recipient — 同 ref_event 不同 recipient 可並存")
    void notification_uniqueConstraint_allowsCrossRecipient() {
        notifRepo.save(Notification.create("alice", "flags", "通知1", null, "sk-1", "evt-100"));
        notifRepo.save(Notification.create("bob", "flags", "通知2", null, "sk-1", "evt-100"));
        // 不應拋；alice + bob 對 same event 各收一筆
        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(1);
        assertThat(notifRepo.countUnreadByRecipient("bob")).isEqualTo(1);
    }

    @Test
    @DisplayName("countUnreadByRecipient + markAllReadForUser 走 partial index path")
    void notification_unreadCount_markAll() {
        notifRepo.save(Notification.create("alice", "flags", "n1", null, "sk-1", "e1"));
        notifRepo.save(Notification.create("alice", "reviews", "n2", null, "sk-1", "e2"));
        notifRepo.save(Notification.create("alice", "requests", "n3", null, null, "e3"));

        assertThat(notifRepo.countUnreadByRecipient("alice")).isEqualTo(3);

        var updated = notifRepo.markAllReadForUser("alice", java.time.Instant.now());
        assertThat(updated).isEqualTo(3);
        assertThat(notifRepo.countUnreadByRecipient("alice")).isZero();
    }

    @Test
    @DisplayName("NotificationPreference.defaults + save → 全 ON；isEnabled switch 正確")
    void preference_defaults_allEnabled() {
        var p = prefRepo.save(NotificationPreference.defaults("alice"));

        assertThat(p.isFlagsEnabled()).isTrue();
        assertThat(p.isReviewsEnabled()).isTrue();
        assertThat(p.isRequestsEnabled()).isTrue();
        assertThat(p.isVersionsEnabled()).isTrue();
        assertThat(p.isEnabled("flags")).isTrue();
        assertThat(p.isEnabled("unknown")).isTrue(); // 保守 fallback
    }

    @Test
    @DisplayName("NotificationPreference.update + save → UPDATE；@Version 走 UPDATE path 不重建")
    void preference_update_thenSave() {
        prefRepo.save(NotificationPreference.defaults("alice"));
        var loaded = prefRepo.findById("alice").orElseThrow();

        loaded.update(false, null, null, false);
        prefRepo.save(loaded);

        var reread = prefRepo.findById("alice").orElseThrow();
        assertThat(reread.isFlagsEnabled()).isFalse();
        assertThat(reread.isVersionsEnabled()).isFalse();
        // 不動的欄位保留
        assertThat(reread.isReviewsEnabled()).isTrue();
        assertThat(reread.isRequestsEnabled()).isTrue();
    }
}
