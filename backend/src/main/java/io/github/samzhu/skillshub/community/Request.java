package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.samzhu.skillshub.community.events.RequestCommentedEvent;
import io.github.samzhu.skillshub.community.events.RequestPostedEvent;

/**
 * S096g2 → S156c — Request aggregate root（Spring Data JDBC state-based 充血聚合）。
 *
 * <p>S156c voting-board pivot：拆除 claim/release/fulfill 機制（含 status / claimerId /
 * fulfilledSkillId 三 fields）；aggregate 只保留 {@link #create} factory。Comment 機制
 * 由 T02 新增 {@code addComment} 充血 method。詳 spec
 * {@code docs/grimo/specs/2026-05-12-S156c-request-voting-board.md} §2.2 / §2.3。
 *
 * <p>對齊 ADR-002 canonical pattern：透過 factory + 充血方法 + registerEvent；service 端
 * 3-line orchestration {@code repo.save()} 觸發 {@code @DomainEvents} 自動 publish 至
 * Modulith outbox（同 TX）。
 *
 * <p>{@code voteCount} 為 {@link ReadOnlyProperty} — {@link RequestVoteService} 走 raw SQL
 * atomic UPDATE 維護（mirror Skill downloadCount S077 pattern）；aggregate save 不覆蓋
 * 並發 increment。
 */
@Table("requests")
public class Request extends AbstractAggregateRoot<Request> {

    private static final int TITLE_MAX = 200;
    private static final int DESCRIPTION_MAX = 5000;

    @Id
    private String id;
    private String title;
    private String description;
    @Column("requester_id")
    private String requesterId;
    @ReadOnlyProperty
    @Column("vote_count")
    private long voteCount;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
    @Version
    @JsonIgnore
    private Long version;

    @PersistenceCreator
    private Request() {}

    /** S096g2 AC-1/AC-2 — 建立新 Request；vote_count=0；註冊 RequestPostedEvent。 */
    public static Request create(String title, String description, String requesterId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title_required");
        }
        var trimmedTitle = title.trim();
        if (trimmedTitle.length() > TITLE_MAX) {
            throw new IllegalArgumentException(
                    "title_too_long: title exceeds " + TITLE_MAX + " characters (got: " + trimmedTitle.length() + ")");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description_required");
        }
        var trimmedDesc = description.trim();
        if (trimmedDesc.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(
                    "description_too_long: description exceeds " + DESCRIPTION_MAX + " characters");
        }
        if (requesterId == null || requesterId.isBlank()) {
            throw new IllegalArgumentException("requesterId is required");
        }

        var r = new Request();
        r.id = UUID.randomUUID().toString();
        r.title = trimmedTitle;
        r.description = trimmedDesc;
        r.requesterId = requesterId.trim();
        r.voteCount = 0;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        r.version = null; // INSERT path — Spring Data 寫回 0
        r.registerEvent(new RequestPostedEvent(r.id, r.title, r.requesterId, r.createdAt));
        return r;
    }

    /**
     * S156c AC-5 — 註冊 {@link RequestCommentedEvent}（outbox publish 由 repo.save 觸發）；
     * comment row 由 {@link CommentService} 同 TX 寫入 {@code request_comments} 表（CommentService
     * 端先 insert RequestComment row 再 repo.save(request) 確保 event 與 row 同 TX）。
     *
     * <p>不持有 comment list — 對齊 Skill aggregate 不持有 download history pattern（read-side
     * projection 走 {@link RequestCommentRepository#findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc}）。
     */
    public void addComment(String commentId, String authorId, String content) {
        if (commentId == null || commentId.isBlank()) {
            throw new IllegalArgumentException("commentId is required");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("authorId is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content_required");
        }
        this.updatedAt = Instant.now();
        registerEvent(new RequestCommentedEvent(commentId, this.id, authorId, content, this.updatedAt));
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getRequesterId() { return requesterId; }
    public long getVoteCount() { return voteCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
