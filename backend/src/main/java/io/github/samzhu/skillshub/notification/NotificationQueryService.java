package io.github.samzhu.skillshub.notification;

import java.util.List;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S096h2-T03 — Notification query 服務（per spec §4.6）。
 *
 * <p>Cursor pagination 走 {@link NotificationRepository#findByRecipientAfterCursor} —
 * {@code (created_at, id) < cursor row} tuple compare，避 OFFSET 災難（兩 page 之間 INSERT
 * 會錯位）。{@code limit + 1} 多查一筆 derive {@code hasNext} = Slice pattern，避 COUNT(*)
 * 災難（千筆通知 user 進 settings 不需知總筆數）。
 *
 * <p>category null = 全列；非 null 走 SQL 內 {@code (:category IS NULL OR category = :category)}
 * 條件。
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository notifRepo;
    private final CurrentUserProvider users;

    NotificationQueryService(NotificationRepository notifRepo, CurrentUserProvider users) {
        this.notifRepo = notifRepo;
        this.users = users;
    }

    /** S096h2 AC-list — cursor pagination + optional category filter。 */
    public Page list(String category, String cursor, int limit) {
        var actor = users.userId();
        int over = limit + 1;
        List<Notification> rows = (cursor == null || cursor.isBlank())
                ? notifRepo.findByRecipient(actor, category, over)
                : notifRepo.findByRecipientAfterCursor(actor, category, cursor, over);
        boolean hasNext = rows.size() > limit;
        var trimmed = hasNext ? rows.subList(0, limit) : rows;
        return new Page(List.copyOf(trimmed), hasNext);
    }

    /** S096h2 — Bell badge unread-count（partial index path）。 */
    public long unreadCount() {
        return notifRepo.countUnreadByRecipient(users.userId());
    }

    /** Slice 結果包：items + 是否有下一頁；nextCursor 由 caller 用 last item id 推導。 */
    public record Page(List<Notification> items, boolean hasNext) {}
}
