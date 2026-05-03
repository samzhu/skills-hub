package io.github.samzhu.skillshub.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096h2-T03 — Notifications REST endpoints（取代 S096h1 stub；per spec §4.1）。
 *
 * <p>7 endpoints：
 * <ul>
 *   <li>{@code GET    /api/v1/notifications}              — list (cursor pagination + category filter)</li>
 *   <li>{@code GET    /api/v1/notifications/unread-count} — bell badge count</li>
 *   <li>{@code POST   /api/v1/notifications/{id}/read}    — mark single read</li>
 *   <li>{@code POST   /api/v1/notifications/read-all}     — mark all read</li>
 *   <li>{@code DELETE /api/v1/notifications/{id}}         — hard delete</li>
 *   <li>{@code GET    /api/v1/notifications/preferences}  — get current user prefs</li>
 *   <li>{@code POST   /api/v1/notifications/preferences}  — partial update</li>
 * </ul>
 *
 * <p>Owner 守則 + ID 不存在處理由 {@link NotificationService} 拋業務 exception；
 * {@code GlobalExceptionHandler} 翻 404 / 403。Controller 純 routing + DTO mapping。
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final NotificationService service;
    private final NotificationQueryService queryService;

    NotificationController(NotificationService service, NotificationQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    /** AC-list — cursor-paginated list with optional category filter；limit clamp [1, 50]。 */
    @GetMapping
    NotificationListResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int n = (limit == null) ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
        var page = queryService.list(category, cursor, n);
        var items = page.items().stream().map(NotificationResponse::from).toList();
        return new NotificationListResponse(items, page.hasNext());
    }

    /** Bell badge — 30s poll path（per spec §4.1）。 */
    @GetMapping("/unread-count")
    UnreadCount unreadCount() {
        return new UnreadCount(queryService.unreadCount());
    }

    /** AC-6 — Mark single as read。404 / 403 / 204。 */
    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable String id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    /** AC-7 — Mark all unread as read for current user。 */
    @PostMapping("/read-all")
    ResponseEntity<Void> markAllRead() {
        service.markAllRead();
        return ResponseEntity.noContent().build();
    }

    /** AC-8 — Hard delete single notification。 */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** AC-9 GET — Current user preferences；無 row 回 defaults（不 INSERT）。 */
    @GetMapping("/preferences")
    PreferencesResponse getPreferences() {
        return PreferencesResponse.from(service.getPreferences());
    }

    /** AC-9 POST — Partial update（null 欄位不動）；upsert if no row。 */
    @PostMapping("/preferences")
    PreferencesResponse updatePreferences(@RequestBody PreferencesUpdateBody body) {
        var updated = service.updatePreferences(body.flags(), body.reviews(),
                body.requests(), body.versions());
        return PreferencesResponse.from(updated);
    }

    /** Public DTO — 對齊 frontend Notification interface（spec §4.7）。 */
    record NotificationResponse(
            String id,
            String category,
            String title,
            String body,
            String skillId,
            String refEventId,
            Instant readAt,
            Instant createdAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getCategory(), n.getTitle(),
                    n.getBody(), n.getSkillId(), n.getRefEventId(),
                    n.getReadAt(), n.getCreatedAt());
        }
    }

    /** List wrapper — items + hasNext flag（per AC-list；nextCursor 由 FE 取 last item id）。 */
    record NotificationListResponse(List<NotificationResponse> items, boolean hasNext) {}

    record UnreadCount(long count) {}

    record PreferencesResponse(boolean flags, boolean reviews, boolean requests, boolean versions) {
        static PreferencesResponse from(NotificationPreference p) {
            return new PreferencesResponse(p.isFlagsEnabled(), p.isReviewsEnabled(),
                    p.isRequestsEnabled(), p.isVersionsEnabled());
        }
    }

    /** Partial update body — null 表「不動」；至少一欄非 null 才有實際 effect（service 端不 enforce）。 */
    record PreferencesUpdateBody(Boolean flags, Boolean reviews, Boolean requests, Boolean versions) {}
}
