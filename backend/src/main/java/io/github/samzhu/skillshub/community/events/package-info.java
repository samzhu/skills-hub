/**
 * community::events named interface — Request domain event records 暴露給跨模組訂閱者
 * （S096h2 NotificationProjectionListener / S024 AuditEventListener 為主要 consumer）。
 *
 * <p>S156c voting-board pivot 後現有 3 個 events：{@code RequestPostedEvent} /
 * {@code RequestVotedEvent} / {@code RequestCommentedEvent}（claim/release/fulfill 三 events
 * 已隨機制拆除）。
 *
 * <p>Aggregate / Repository / Service 仍 internal — 透過 {@code community :: domain} 或
 * 改 events 走 application listener 訂閱。對齊 skill::domain 既有 NamedInterface pattern。
 */
@org.springframework.modulith.NamedInterface("events")
package io.github.samzhu.skillshub.community.events;
