package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S125a — SkillSubscription aggregate（user-skill 關注關係）。
 *
 * <p>對齊 ADR-002 canonical pattern + community 既驗 Collection / Request pattern：
 * <ul>
 *   <li>{@code extends AbstractAggregateRoot<SkillSubscription>} — 提供
 *       {@code registerEvent(...)}（本 aggregate 目前不發 domain event；保留 future
 *       SkillSubscribedEvent / SkillUnsubscribedEvent 擴展空間）</li>
 *   <li>{@code @Version Long version} — Spring Data JDBC 樂觀鎖標記；INSERT/UPDATE
 *       canonical 路徑（per ADR-002 + 既有 Collection/Request pattern）</li>
 *   <li>無 mutable state — subscribe = create + save row；unsubscribe = repo.delete row；
 *       無需 mutation method</li>
 * </ul>
 *
 * <p><b>用途</b>：S125b NotificationProjectionListener.onVersionPublished 訂閱
 * SkillVersionPublishedEvent 後，從 SkillSubscriptionRepository 查 subscribers list →
 * 對每個 subscriber 寫 notification。對 PRD §285-§291 P9 SBE scenario 1 補完。
 *
 * <p><b>UNIQUE invariant</b>：DB UNIQUE(skill_id, subscriber_id) 守同 user 不重複訂閱；
 * service layer 加 idempotent 保護（existsBySkillIdAndSubscriberId 預檢）避免拋
 * DataIntegrityViolationException 至 caller。
 */
@Table("skill_subscriptions")
public class SkillSubscription extends AbstractAggregateRoot<SkillSubscription> {

    @Id
    private String id;

    private String skillId;

    private String subscriberId;

    private Instant createdAt;

    @Version
    @JsonIgnore
    private Long version;

    /**
     * Factory — 新訂閱關係。caller 須先檢查 skill 存在 + subscriber 未重複訂閱
     * （走 SubscriptionService.subscribe 路徑，不直接 new SkillSubscription）。
     */
    public static SkillSubscription create(String skillId, String subscriberId) {
        var s = new SkillSubscription();
        s.id = UUID.randomUUID().toString();
        s.skillId = skillId;
        s.subscriberId = subscriberId;
        s.createdAt = Instant.now();
        return s;
    }

    /** Spring Data JDBC 反序列化 ctor — 不開放給 production code 直接呼叫。 */
    @PersistenceCreator
    SkillSubscription(String id, String skillId, String subscriberId, Instant createdAt, Long version) {
        this.id = id;
        this.skillId = skillId;
        this.subscriberId = subscriberId;
        this.createdAt = createdAt;
        this.version = version;
    }

    /** Default ctor — Spring Data 反射用。 */
    SkillSubscription() {}

    public String getId() {
        return id;
    }

    public String getSkillId() {
        return skillId;
    }

    public String getSubscriberId() {
        return subscriberId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
