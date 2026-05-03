package io.github.samzhu.skillshub.shared.api;

/**
 * S096h2-T03 — Notification mark-read / delete 操作被拒（actor 非 recipient）。
 * GlobalExceptionHandler → 403 + {@code error: "not_notification_recipient"}。
 */
public class NotNotificationRecipientException extends RuntimeException {
    public NotNotificationRecipientException() {
        super("not_notification_recipient");
    }
}
