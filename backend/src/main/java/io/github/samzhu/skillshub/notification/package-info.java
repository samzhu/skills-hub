/**
 * Notification module — 跨 module event-driven projection（per S096h2 → S156c）。
 *
 * <p>S156c voting-board pivot：移除對 {@code RequestClaimedEvent} / {@code RequestFulfilledEvent}
 * 的依賴（claim/fulfill 機制已拆）；新增 {@code RequestCommentedEvent} listener 通知 requester。
 *
 * <p>{@link NotificationProjectionListener} 4 個 {@code @ApplicationModuleListener}
 * 訂閱 {@code SkillFlaggedEvent} / {@code ReviewCreatedEvent} / {@code SkillVersionPublishedEvent}
 * / {@code RequestCommentedEvent}。
 *
 * <p>Cross-module deps：
 * <ul>
 *   <li>{@code shared :: events / security / api} — Persistable / GlobalExceptionHandler / CurrentUserProvider</li>
 *   <li>{@code skill :: domain}（SkillRepository owner_id lookup + SkillVersionPublishedEvent）</li>
 *   <li>{@code community}（SkillSubscriptionService + RequestRepository requester_id lookup）</li>
 *   <li>{@code community :: events}（RequestCommentedEvent — S156c）</li>
 *   <li>{@code review :: domain}（ReviewCreatedEvent）</li>
 *   <li>{@code security}（SkillFlaggedEvent）</li>
 * </ul>
 *
 * <p>對齊 audit module 既有「跨模組訂閱者」pattern；以 module boundary 把 notification
 * 業務邏輯（recipient 計算 / preferences gate / idempotency 鍵）與其他 producer 模組隔離。
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {
        "shared :: events", "shared :: security", "shared :: api",
        "skill :: domain",         // SkillRepository owner_id (author) lookup + SkillVersionPublishedEvent
        "community",               // SkillSubscriptionService (S125b) + RequestRepository requester_id lookup (S156c)
        "community :: events",     // RequestCommentedEvent (S156c)
        "review :: domain",        // ReviewCreatedEvent (review.domain 為 NamedInterface)
        "security"                 // SkillFlaggedEvent record (security 模組 top-level)
    }
)
package io.github.samzhu.skillshub.notification;
