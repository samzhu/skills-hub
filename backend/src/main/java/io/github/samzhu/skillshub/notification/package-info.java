/**
 * Notification module — 跨 module event-driven projection（per S096h2）。
 *
 * <p>T01 ship 註冊 module + 2 aggregate (Notification + NotificationPreference) +
 * V11 schema。{@link NotificationProjectionListener} 4 個 {@code @ApplicationModuleListener}
 * 訂閱 {@code SkillFlaggedEvent} / {@code ReviewCreatedEvent} / {@code RequestClaimedEvent} /
 * {@code RequestFulfilledEvent} 由 T02 補；service mutation endpoints 由 T03 補。
 *
 * <p>Cross-module deps（T01 minimum，T02 加 listener 時擴）：
 * <ul>
 *   <li>{@code shared :: events / security / api} — Persistable / GlobalExceptionHandler / CurrentUserProvider</li>
 *   <li>T02 將加：{@code skill :: domain}（SkillRepository owner_id lookup）+ {@code community}
 *       （RequestRepository requester_id lookup）+ {@code review}（ReviewCreatedEvent record）+
 *       {@code security}（SkillFlaggedEvent record）</li>
 * </ul>
 *
 * <p>對齊 audit module 既有「跨模組訂閱者」pattern；以 module boundary 把 notification
 * 業務邏輯（recipient 計算 / preferences gate / idempotency 鍵）與其他 producer 模組隔離。
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {"shared :: events", "shared :: security", "shared :: api"}
)
package io.github.samzhu.skillshub.notification;
