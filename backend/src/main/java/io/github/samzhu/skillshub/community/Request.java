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

import io.github.samzhu.skillshub.community.events.RequestClaimedEvent;
import io.github.samzhu.skillshub.community.events.RequestFulfilledEvent;
import io.github.samzhu.skillshub.community.events.RequestPostedEvent;
import io.github.samzhu.skillshub.community.events.RequestReleasedEvent;
import io.github.samzhu.skillshub.shared.api.NotRequestClaimerException;

/**
 * S096g2 — Request aggregate root（Spring Data JDBC state-based 充血聚合）。
 *
 * <p>對齊 ADR-002 canonical pattern + Skill aggregate 範本：透過 factory + 充血方法
 * 修改 state + registerEvent；service 端 3-line orchestration `repo.save()` 觸發
 * `@DomainEvents` 自動 publish 至 Modulith outbox（同 TX）。
 *
 * <p>State machine（OPEN ⇄ IN_PROGRESS → FULFILLED）：
 * <ul>
 *   <li>{@link #claim} OPEN → IN_PROGRESS</li>
 *   <li>{@link #release} IN_PROGRESS → OPEN（claimer 比對）</li>
 *   <li>{@link #fulfill} IN_PROGRESS → FULFILLED（claimer 比對 + 綁 PUBLISHED skill）</li>
 * </ul>
 *
 * <p>{@code voteCount} 為 {@link ReadOnlyProperty} — T02 RequestVoteService 走 raw SQL atomic
 * UPDATE 維護（mirror Skill downloadCount S077 pattern）；aggregate save 不覆蓋並發 increment。
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
    private String status; // OPEN / IN_PROGRESS / FULFILLED
    @Column("claimer_id")
    private String claimerId;
    @Column("fulfilled_skill_id")
    private String fulfilledSkillId;
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

    /** S096g2 AC-1/AC-2 — 建立新 Request；status=OPEN, vote_count=0；註冊 RequestPostedEvent。 */
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
        r.status = "OPEN";
        r.claimerId = null;
        r.fulfilledSkillId = null;
        r.voteCount = 0;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        r.version = null; // INSERT path — Spring Data 寫回 0
        r.registerEvent(new RequestPostedEvent(r.id, r.title, r.requesterId, r.createdAt));
        return r;
    }

    /** S096g2 AC-7/AC-8 — 認領 OPEN→IN_PROGRESS；非 OPEN 拋 IllegalStateException → 409。 */
    public void claim(String userId) {
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("request_already_claimed: status=" + status);
        }
        this.status = "IN_PROGRESS";
        this.claimerId = userId;
        this.updatedAt = Instant.now();
        registerEvent(new RequestClaimedEvent(id, userId, updatedAt));
    }

    /** S096g2 AC-9 — 釋放認領 IN_PROGRESS→OPEN；非 IN_PROGRESS 拋 IllegalStateException；非 claimer 拋 NotRequestClaimerException。 */
    public void release(String userId) {
        if (!"IN_PROGRESS".equals(status)) {
            throw new IllegalStateException("not_in_progress: status=" + status);
        }
        if (!userId.equals(claimerId)) {
            throw new NotRequestClaimerException();
        }
        this.status = "OPEN";
        this.claimerId = null;
        this.updatedAt = Instant.now();
        registerEvent(new RequestReleasedEvent(id, userId, updatedAt));
    }

    /** S096g2 AC-10/AC-11 — 完成 IN_PROGRESS→FULFILLED；綁 PUBLISHED skillId。 */
    public void fulfill(String userId, String publishedSkillId) {
        if (!"IN_PROGRESS".equals(status)) {
            throw new IllegalStateException("not_in_progress: status=" + status);
        }
        if (!userId.equals(claimerId)) {
            throw new NotRequestClaimerException();
        }
        if (publishedSkillId == null || publishedSkillId.isBlank()) {
            throw new IllegalArgumentException("skillId is required");
        }
        this.status = "FULFILLED";
        this.fulfilledSkillId = publishedSkillId;
        this.updatedAt = Instant.now();
        registerEvent(new RequestFulfilledEvent(id, userId, publishedSkillId, updatedAt));
    }

    /** S096g2 AC-13 — 只能 delete OWN OPEN（非 OPEN 拋 IllegalStateException → 409）；非 requester 由 service 層守。 */
    public void assertDeletable() {
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("cannot_delete_active_request: status=" + status);
        }
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getRequesterId() { return requesterId; }
    public String getStatus() { return status; }
    public String getClaimerId() { return claimerId; }
    public String getFulfilledSkillId() { return fulfilledSkillId; }
    public long getVoteCount() { return voteCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
