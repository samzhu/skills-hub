package io.github.samzhu.skillshub.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096h1 — Notification stub controller (read-only empty list + unread count 0).
 *
 * <p>per PRD §P9 Notifications + Engineering Handoff §2.17. 完整 projection 從
 * domain_events + per-user subscription filter + WebSocket / poll 真資料留 S096h2.
 *
 * <p>新 module path `notification/`；對應 S096 META §5.1「new notification module
 * 為第 8 個」設計。Modulith `@ApplicationModule` 標註留 S096h2 補（與 community
 * 群組同步處理）。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

	/**
	 * List notifications filtered by tab (all|versions|flags|reviews|requests).
	 * S096h1: returns empty list (stub); query param ignored until S096h2.
	 */
	@GetMapping
	List<NotificationSummary> list() {
		return List.of();
	}

	/**
	 * Unread count for bell badge — poll every 30s by frontend (per Engineering
	 * Handoff §2.17). S096h1 stub returns 0; real projection from domain_events
	 * + user subscription join 留 S096h2.
	 */
	@GetMapping("/unread-count")
	UnreadCount unreadCount() {
		return new UnreadCount(0);
	}

	/**
	 * Notification public summary — frontend NotificationsPage row schema.
	 *
	 * @param id        unique notification id (UUID)
	 * @param category  notification category (versions/flags/reviews/requests)
	 * @param title     short title
	 * @param body      long-form description
	 * @param skillId   target skill id (optional)
	 * @param read      read status
	 * @param createdAt notification time
	 */
	public record NotificationSummary(
			String id,
			String category,
			String title,
			String body,
			String skillId,
			boolean read,
			Instant createdAt) {}

	public record UnreadCount(int count) {}
}
