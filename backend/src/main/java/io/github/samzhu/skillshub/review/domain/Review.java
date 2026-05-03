package io.github.samzhu.skillshub.review.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * S098e2 — Review aggregate root（Spring Data JDBC state-based 充血聚合）。
 *
 * <p>對齊 ADR-002 canonical pattern（Skill aggregate 範本）：透過 {@link #create} factory
 * 建立 + {@code registerEvent} 加 {@link ReviewCreatedEvent}；service 端 3-line orchestration
 * `repo.save()` 觸發 {@code @DomainEvents} 自動 publish 至 Modulith outbox（同 TX）。
 *
 * <p>業務不變量：
 * <ul>
 *   <li>{@code rating} ∈ [1, 5]（DB CHECK + factory validate 雙保險）</li>
 *   <li>{@code content} 長度 1-2000 字元（cap 對齊 Flag description 同數量級）</li>
 *   <li>{@code (skill_id, author_id)} UNIQUE — 每 user 每 skill 1 則（DB constraint）</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Table("reviews")
public class Review extends AbstractAggregateRoot<Review> implements Persistable<String> {

    /** AC-3：對齊 Flag.DESCRIPTION_MAX×4 — Review 通常更長 prose，2000 字夠承載一段段落式評論。 */
    private static final int CONTENT_MAX = 2000;

    @Id
    private String id;
    @Column("skill_id")
    private String skillId;
    @Column("author_id")
    private String authorId;
    private int rating;
    private String content;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    /** Spring Data JDBC entity creator — framework 透過 reflection 呼叫；持久化欄位由 field reflection 填入。 */
    @PersistenceCreator
    private Review() {
        // No-op
    }

    /**
     * 建立新 Review aggregate；註冊 {@link ReviewCreatedEvent}。
     *
     * @throws IllegalArgumentException AC-2 rating 不在 [1, 5]；AC-3 content 為 null/blank/超 2000 字元
     */
    public static Review create(String skillId, String authorId, int rating, String content) {
        // AC-2：rating range — IllegalArgumentException 走 GlobalExceptionHandler 400 VALIDATION_ERROR
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating_out_of_range: rating must be in [1, 5] (got: " + rating + ")");
        }
        // AC-3：content 必填 + 長度上限
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content_required: content must not be blank");
        }
        var trimmed = content.trim();
        if (trimmed.length() > CONTENT_MAX) {
            throw new IllegalArgumentException(
                    "content_too_long: content exceeds " + CONTENT_MAX + " characters (got: " + trimmed.length() + ")");
        }
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId is required");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("authorId is required");
        }

        var review = new Review();
        review.id = UUID.randomUUID().toString();
        review.skillId = skillId;
        review.authorId = authorId;
        review.rating = rating;
        review.content = trimmed;
        review.createdAt = Instant.now();
        review.updatedAt = review.createdAt;
        review.registerEvent(new ReviewCreatedEvent(
                review.id, skillId, authorId, rating, trimmed, review.createdAt));
        return review;
    }

    public String getId() { return id; }
    public String getSkillId() { return skillId; }
    public String getAuthorId() { return authorId; }
    public int getRating() { return rating; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    @org.springframework.data.annotation.Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isNew() {
        // T01 scope：aggregate 只走 INSERT (factory create) 路徑，無 mutation save。
        // Delete flow 由 service deleteById 直接處理（不經 save）；event 由 service 端 publish。
        // T02 / future spec 引入 update 時需加 @Version + isNew = (version == null)。
        return persistenceFlag == 0;
    }

    /**
     * Persistence guard — factory 設 0（new，INSERT path）；@PersistenceCreator 因走 reflection
     * 不執行 java field initializer 導致此值預設 0；用 @PostLoad 風格 hook 不存在於 Spring Data JDBC，
     * 改在 ReviewRepository 內 transient setter set 為 1 — 但 framework load 後不會自動呼叫。
     * 折衷：load 後 isNew false，僅靠 framework 內部 entity callback。實際 T01 不會 save() loaded
     * entity（delete 直接 deleteById），所以此 flag 唯一影響是 factory create 路徑 → return 0 = isNew true。
     */
    @org.springframework.data.annotation.Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    private int persistenceFlag = 0;
}
