/**
 * review::domain named interface — Review aggregate + ReviewCreatedEvent / ReviewDeletedEvent
 * + ReviewRepository 暴露給跨模組 consumer（skill 模組已透過 SPI 訂閱 ratings；
 * S096h2 NotificationProjectionListener 訂閱 ReviewCreatedEvent）。
 *
 * <p>對齊 skill::domain NamedInterface pattern；review 模組其他內部 type（ReviewService /
 * ReviewController）仍維持 internal。
 */
@org.springframework.modulith.NamedInterface("domain")
package io.github.samzhu.skillshub.review.domain;
