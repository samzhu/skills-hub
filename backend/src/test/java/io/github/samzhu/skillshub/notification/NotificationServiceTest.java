package io.github.samzhu.skillshub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.api.NotNotificationRecipientException;
import io.github.samzhu.skillshub.shared.api.NotificationNotFoundException;

/**
 * S096h2-T03 — NotificationService + NotificationQueryService 業務邏輯整合測試。
 *
 * <p>對齊 RequestServiceTest 既有 pattern（{@code @SpringBootTest} + Testcontainers + 真 PostgreSQL）。
 * 涵蓋 AC：6（mark-read happy + 403 + 404）/ 7（mark-all-read 只動 unread）/
 * 8（delete happy + 403 + 404）/ 9（preferences upsert + partial update + getPreferences 不 INSERT）/
 * list（cursor pagination + hasNext flag + category filter）。
 *
 * <p>Auth 透過 {@link SecurityContextHolder} 直接 inject {@link UsernamePasswordAuthenticationToken}
 * 模擬「當前 user」— {@link io.github.samzhu.skillshub.shared.security.CurrentUserProvider} 第二
 * branch 路徑（per OAuth disabled 的 LAB-mode 測試慣例）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationServiceTest {

    @Autowired
    private NotificationService service;

    @Autowired
    private NotificationQueryService queryService;

    @Autowired
    private NotificationRepository notifRepo;

    @Autowired
    private NotificationPreferenceRepository prefRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
        loginAs("alice");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: alice mark own → read_at 戳記 + unread-count 變 0")
    void markRead_happy() {
        var n = notifRepo.save(Notification.create("alice", "flags", "n1", null, null, "e1"));

        service.markRead(n.getId());

        var reread = notifRepo.findById(n.getId()).orElseThrow();
        assertThat(reread.isRead()).isTrue();
        assertThat(reread.getReadAt()).isNotNull();
        assertThat(queryService.unreadCount()).isZero();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: 非 owner mark → NotNotificationRecipientException")
    void markRead_byNonOwner_rejected() {
        var n = notifRepo.save(Notification.create("alice", "flags", "n1", null, null, "e1"));
        loginAs("bob");

        assertThatThrownBy(() -> service.markRead(n.getId()))
                .isInstanceOf(NotNotificationRecipientException.class);
        // alice 的 read_at 仍 null
        assertThat(notifRepo.findById(n.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: 不存在 id mark → NotificationNotFoundException")
    void markRead_notFound() {
        assertThatThrownBy(() -> service.markRead("non-existent"))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: markAllRead → 5 unread + 2 already-read → 5 變 read，2 不動")
    void markAllRead_onlyUpdatesUnread() {
        for (int i = 0; i < 5; i++) {
            notifRepo.save(Notification.create("alice", "flags", "u" + i, null, null, "eu" + i));
        }
        var pre1 = notifRepo.save(Notification.create("alice", "reviews", "r1", null, null, "er1"));
        pre1.markRead();
        notifRepo.save(pre1);
        var pre1Time = notifRepo.findById(pre1.getId()).orElseThrow().getReadAt();
        var pre2 = notifRepo.save(Notification.create("alice", "reviews", "r2", null, null, "er2"));
        pre2.markRead();
        notifRepo.save(pre2);

        var updated = service.markAllRead();

        assertThat(updated).isEqualTo(5);
        assertThat(queryService.unreadCount()).isZero();
        // already-read 的 read_at 不應被覆蓋
        var pre1After = notifRepo.findById(pre1.getId()).orElseThrow();
        assertThat(pre1After.getReadAt()).isEqualTo(pre1Time);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: alice delete own → row 消失")
    void delete_happy() {
        var n = notifRepo.save(Notification.create("alice", "flags", "n1", null, null, "e1"));

        service.delete(n.getId());

        assertThat(notifRepo.findById(n.getId())).isEmpty();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: 非 owner delete → 403；row 還在")
    void delete_byNonOwner_rejected() {
        var n = notifRepo.save(Notification.create("alice", "flags", "n1", null, null, "e1"));
        loginAs("bob");

        assertThatThrownBy(() -> service.delete(n.getId()))
                .isInstanceOf(NotNotificationRecipientException.class);
        assertThat(notifRepo.findById(n.getId())).isPresent();
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: 不存在 id delete → NotificationNotFoundException")
    void delete_notFound() {
        assertThatThrownBy(() -> service.delete("non-existent"))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: 無 row → updatePreferences upsert，DB row 落地，未指定欄位走 defaults")
    void updatePreferences_upsertNew_partialBody() {
        assertThat(prefRepo.findById("alice")).isEmpty();

        // 只指定 flags，其他 null
        var p = service.updatePreferences(false, null, null, null);

        assertThat(p.isFlagsEnabled()).isFalse();
        assertThat(p.isReviewsEnabled()).isTrue();   // defaults factory 全 ON
        assertThat(p.isRequestsEnabled()).isTrue();
        assertThat(p.isVersionsEnabled()).isTrue();
        assertThat(prefRepo.findById("alice")).isPresent();
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: 已有 row → partial update 只動指定欄位")
    void updatePreferences_partialUpdateExistingRow() {
        prefRepo.save(NotificationPreference.defaults("alice"));

        // 只關 reviews
        service.updatePreferences(null, false, null, null);

        var reread = prefRepo.findById("alice").orElseThrow();
        assertThat(reread.isFlagsEnabled()).isTrue();
        assertThat(reread.isReviewsEnabled()).isFalse();
        assertThat(reread.isRequestsEnabled()).isTrue();
        assertThat(reread.isVersionsEnabled()).isTrue();
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: getPreferences 無 row → 回 in-memory defaults，DB 不 INSERT")
    void getPreferences_defaults_noInsertSideEffect() {
        var p = service.getPreferences();

        assertThat(p.isFlagsEnabled()).isTrue();
        assertThat(p.isReviewsEnabled()).isTrue();
        assertThat(p.isRequestsEnabled()).isTrue();
        assertThat(p.isVersionsEnabled()).isTrue();
        // 無副作用：DB 仍空
        assertThat(prefRepo.findById("alice")).isEmpty();
    }

    @Test
    @Tag("AC-list")
    @DisplayName("AC-list: 25 筆 → limit=10 first page → 10 + hasNext=true；cursor 第二頁 10；第三頁 5 + hasNext=false")
    void list_cursorPagination() throws InterruptedException {
        // 25 筆 同 alice，依 createdAt ascending（newest in last loop）→ list 將 desc 顯示
        for (int i = 0; i < 25; i++) {
            notifRepo.save(Notification.create("alice", "flags",
                    "n-" + i, null, null, "e-" + i));
            Thread.sleep(2); // 確保 createdAt monotonic strict ordering
        }

        var page1 = queryService.list(null, null, 10);
        assertThat(page1.items()).hasSize(10);
        assertThat(page1.hasNext()).isTrue();

        var cursor1 = page1.items().get(page1.items().size() - 1).getId();
        var page2 = queryService.list(null, cursor1, 10);
        assertThat(page2.items()).hasSize(10);
        assertThat(page2.hasNext()).isTrue();

        var cursor2 = page2.items().get(page2.items().size() - 1).getId();
        var page3 = queryService.list(null, cursor2, 10);
        assertThat(page3.items()).hasSize(5);
        assertThat(page3.hasNext()).isFalse();

        // 三 page 合起來 25 unique，照 desc 順序（最新在 page1 第一筆）
        var allIds = List.of(page1.items(), page2.items(), page3.items()).stream()
                .flatMap(List::stream)
                .map(Notification::getId)
                .toList();
        assertThat(allIds).hasSize(25).doesNotHaveDuplicates();
        assertThat(page1.items().get(0).getRefEventId()).isEqualTo("e-24"); // 最後 INSERT 的最新
    }

    @Test
    @Tag("AC-list")
    @DisplayName("AC-list: category filter — 只回該 category；限 alice 自己")
    void list_categoryFilter_isolation() {
        notifRepo.save(Notification.create("alice", "flags", "f1", null, null, "ef1"));
        notifRepo.save(Notification.create("alice", "reviews", "r1", null, null, "er1"));
        notifRepo.save(Notification.create("alice", "requests", "q1", null, null, "eq1"));
        notifRepo.save(Notification.create("bob", "flags", "bob-f", null, null, "ebobf")); // 不該漏

        var flagsOnly = queryService.list("flags", null, 50);
        assertThat(flagsOnly.items()).hasSize(1);
        assertThat(flagsOnly.items().get(0).getCategory()).isEqualTo("flags");
        assertThat(flagsOnly.items().get(0).getRecipientId()).isEqualTo("alice");

        var allCats = queryService.list(null, null, 50);
        assertThat(allCats.items()).hasSize(3); // bob 那筆不該出現
    }

    private void loginAs(String userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_user")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
