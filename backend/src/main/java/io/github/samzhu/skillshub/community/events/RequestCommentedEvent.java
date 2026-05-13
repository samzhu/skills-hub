package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/**
 * S156c AC-5 — Request comment 建立。由 {@link io.github.samzhu.skillshub.community.Request#addComment}
 * registerEvent；{@code repo.save(request)} 透過 outbox publish。
 *
 * <p>下游 consumer：
 * <ul>
 *   <li>{@code NotificationProjectionListener.onRequestCommented} — 通知 requester（除非 self-comment）</li>
 *   <li>{@code AuditEventListener.onRequestCommented} (T03) — 寫入 {@code domain_events} 永存（spec §2.8）</li>
 * </ul>
 *
 * <p>{@code commentId} 由 {@link io.github.samzhu.skillshub.community.RequestComment#create} 工廠
 * 生成（UUID v4）並透傳至此 event；下游 audit 用 commentId 作 dedupKey（1 comment 1 audit row）。
 *
 * @param commentId  comment row UUID（dedupKey for audit）
 * @param requestId  comment 所屬 request id
 * @param authorId   comment 作者 user_id
 * @param content    comment 內文（S161 PlainTextDeserializer sanitized）
 * @param occurredAt 事件發生時間
 */
public record RequestCommentedEvent(
        String commentId,
        String requestId,
        String authorId,
        String content,
        Instant occurredAt
) {}
