/**
 * community::events named interface — Request 5 個 domain event records 暴露給跨模組訂閱者
 * （S096h2 NotificationProjectionListener 為首批 consumer）。
 *
 * <p>Aggregate / Repository / Service 仍 internal — 透過 {@code community :: domain} 或
 * 改 events 走 application listener 訂閱。對齊 skill::domain 既有 NamedInterface pattern。
 */
@org.springframework.modulith.NamedInterface("events")
package io.github.samzhu.skillshub.community.events;
