package io.github.samzhu.skillshub.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * S096h2 — Notification 讀寫 repo。
 *
 * <p>Cursor pagination 走 created_at DESC + id 做 tiebreaker（同 timestamp 同 ms 防 lost row）。
 * `@Query` annotation 取代 derived query — 對齊 RequestRepository S096g2-T01 既驗 pattern
 * （Spring Boot 4.0.6 AOT codegen 對多屬性 compound sort 有 bug）。
 */
interface NotificationRepository extends CrudRepository<Notification, String> {

    @Query("""
            SELECT * FROM notifications
            WHERE recipient_id = :recipientId
              AND (:category IS NULL OR category = :category)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<Notification> findByRecipient(@Param("recipientId") String recipientId,
                                       @Param("category") String category,
                                       @Param("limit") int limit);

    @Query("""
            SELECT * FROM notifications
            WHERE recipient_id = :recipientId
              AND (:category IS NULL OR category = :category)
              AND (created_at, id) < (
                    SELECT created_at, id FROM notifications WHERE id = :cursorId
              )
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<Notification> findByRecipientAfterCursor(@Param("recipientId") String recipientId,
                                                  @Param("category") String category,
                                                  @Param("cursorId") String cursorId,
                                                  @Param("limit") int limit);

    @Query("""
            SELECT COUNT(*) FROM notifications
            WHERE recipient_id = :recipientId AND read_at IS NULL
            """)
    long countUnreadByRecipient(@Param("recipientId") String recipientId);

    @Modifying
    @Query("""
            UPDATE notifications
            SET read_at = :ts
            WHERE recipient_id = :recipientId AND read_at IS NULL
            """)
    int markAllReadForUser(@Param("recipientId") String recipientId,
                           @Param("ts") Instant ts);

    Optional<Notification> findByRecipientIdAndRefEventIdAndCategory(String recipientId,
                                                                    String refEventId,
                                                                    String category);
}
