package io.github.samzhu.skillshub.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S096h2 — Notification aggregate（per-recipient projection row）。
 *
 * <p>由 {@link NotificationProjectionListener}（T02）AFTER_COMMIT async 訂閱跨 module
 * domain events 後 INSERT；mutation 走 service 端（mark-read / delete）— T03 補。
 *
 * <p>{@code @Version} 給 Spring Data JDBC 區分 INSERT vs UPDATE：factory 建立時 version=null
 * → save() 走 INSERT；loaded entity version=N → save() 走 UPDATE（mark-read path）。
 * 對齊 Request aggregate (S096g2) 既有 pattern；無 {@code Persistable} 介面需求。
 *
 * <p>{@code (recipient_id, ref_event_id, category)} 由 V11 UNIQUE constraint 強制 — listener
 * outbox redelivery INSERT 同事件 → DuplicateKeyException → listener catch + 忽略
 * （AC-10 idempotency；T02 落實）。
 */
@Table("notifications")
public class Notification {

    @Id
    private String id;
    @Column("recipient_id")
    private String recipientId;
    private String category;
    private String title;
    private String body;
    @Column("skill_id")
    private String skillId;
    @Column("ref_event_id")
    private String refEventId;
    @Column("read_at")
    private Instant readAt;
    @Column("created_at")
    private Instant createdAt;
    @Version
    @JsonIgnore
    private Long version;

    @PersistenceCreator
    private Notification() {}

    /**
     * S096h2 listener factory — 建立新 unread Notification（INSERT path）。
     *
     * @param recipientId 收件人 user id（skill.owner_id 或 request.requester_id）
     * @param category    `flags` | `reviews` | `requests` | `versions`（V11 CHECK constraint）
     * @param title       人類可讀標題（zh-TW；含相關 entity name + 動作）
     * @param body        補充說明，可為 null（如 review 內文短不需 body）
     * @param skillId     相關 skill id（nullable — request claim/fulfill 通知無需）
     * @param refEventId  source domain event 識別碼 — UNIQUE 鍵組成防 outbox redelivery
     */
    public static Notification create(String recipientId, String category, String title,
                                      String body, String skillId, String refEventId) {
        if (recipientId == null || recipientId.isBlank()) {
            throw new IllegalArgumentException("recipientId is required");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (refEventId == null || refEventId.isBlank()) {
            throw new IllegalArgumentException("refEventId is required");
        }
        var n = new Notification();
        n.id = UUID.randomUUID().toString();
        n.recipientId = recipientId;
        n.category = category;
        n.title = title;
        n.body = body;
        n.skillId = skillId;
        n.refEventId = refEventId;
        n.readAt = null;
        n.createdAt = Instant.now();
        n.version = null; // INSERT path
        return n;
    }

    /** S096h2 AC-6 — 標記已讀；service 端守 ownership 後呼叫此 mutation + save()。 */
    public void markRead() {
        if (readAt == null) {
            this.readAt = Instant.now();
        }
    }

    /** S096h2 AC-6/AC-8 — 守 ownership：只有 recipient 可 mark read / delete。 */
    public boolean isOwnedBy(String userId) {
        return Objects.equals(userId, recipientId);
    }

    public boolean isRead() {
        return readAt != null;
    }

    public String getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getSkillId() { return skillId; }
    public String getRefEventId() { return refEventId; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
