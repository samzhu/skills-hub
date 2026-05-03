package io.github.samzhu.skillshub.shared.api;

/**
 * S096h2-T03 — Notification id 不存在。GlobalExceptionHandler → 404 + {@code error: "notification_not_found"}。
 */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(String id) {
        super("notification_not_found: " + id);
    }
}
