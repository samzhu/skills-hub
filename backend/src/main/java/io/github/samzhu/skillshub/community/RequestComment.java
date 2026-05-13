package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S156c — Request comment entity（Spring Data JDBC）。
 *
 * <p>Comment row 由 {@link CommentService} 寫入 {@code request_comments} 表；
 * 對應的 {@link io.github.samzhu.skillshub.community.events.RequestCommentedEvent}
 * 在 {@link Request#addComment} 由 Request aggregate registerEvent，與 comment row
 * 同 TX 寫入（{@code repo.save(request)} 觸發 outbox publish）。
 *
 * <p>Soft delete pattern：{@code deleted_at} 不為 null 即視為「已刪」；query layer
 * 走 {@code findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc} 過濾。Hard delete
 * 不暴露 — comment author 自刪僅標記 soft delete；request hard delete 時透過
 * V22 {@code ON DELETE CASCADE} 物理移除（events 仍存在 {@code domain_events} 永存
 * per spec §2.8）。
 *
 * @see CommentService
 * @see io.github.samzhu.skillshub.community.events.RequestCommentedEvent
 */
@Table("request_comments")
public class RequestComment {

    private static final int CONTENT_MAX = 5000;

    @Id
    private String id;
    @Column("request_id")
    private String requestId;
    @Column("author_id")
    private String authorId;
    private String content;
    @Column("created_at")
    private Instant createdAt;
    @Column("deleted_at")
    private Instant deletedAt;
    @Version
    @JsonIgnore
    private Long version;

    @PersistenceCreator
    private RequestComment() {}

    /** Factory — 建立新 comment row；id 走 UUID v4。 */
    public static RequestComment create(String requestId, String authorId, String content) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("authorId is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content_required");
        }
        var trimmed = content.trim();
        if (trimmed.length() > CONTENT_MAX) {
            throw new IllegalArgumentException(
                    "content_too_long: exceeds " + CONTENT_MAX + " characters (got: " + trimmed.length() + ")");
        }
        var c = new RequestComment();
        c.id = UUID.randomUUID().toString();
        c.requestId = requestId;
        c.authorId = authorId.trim();
        c.content = trimmed;
        c.createdAt = Instant.now();
        c.deletedAt = null;
        c.version = null; // INSERT path — @Version 為 INSERT/UPDATE 唯一區分器（per Collection / Request pattern）
        return c;
    }

    /** Soft delete — 標記刪除時間；query layer 過濾 deleted_at IS NOT NULL row。 */
    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
