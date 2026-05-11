package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.security.DisplayNameResolver;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S125a — SkillSubscription 業務服務（subscribe / unsubscribe / lookup）。
 *
 * <p>對齊 community 既驗 service pattern（CollectionService / RequestService）：
 * 3-line orchestration + transactional boundary + service-layer 邏輯（factory call /
 * idempotent 檢查 / aggregate save/delete）。
 *
 * <p><b>Idempotency</b>：subscribe 預檢 existsBySkillIdAndSubscriberId 避免拋
 * DataIntegrityViolationException 至 caller；unsubscribe 找不到 row 安靜 return（noop）。
 *
 * <p><b>暴露給 S125b NotificationProjectionListener</b>：{@link #findSubscribersOf} 為
 * onVersionPublished listener 提供 subscriber lookup → 對每個 subscriber 寫 notification。
 */
@Service
@Transactional
public class SkillSubscriptionService {

    private final SkillSubscriptionRepository repo;
    private final SkillRepository skillRepo;
    private final CurrentUserProvider users;
    private final NamedParameterJdbcTemplate jdbc;

    public SkillSubscriptionService(SkillSubscriptionRepository repo,
                                    SkillRepository skillRepo,
                                    CurrentUserProvider users,
                                    NamedParameterJdbcTemplate jdbc) {
        this.repo = repo;
        this.skillRepo = skillRepo;
        this.users = users;
        this.jdbc = jdbc;
    }

    /** S145 — current user's subscription management row. */
    public record SkillSubscriptionSummary(
            String skillId,
            String skillName,
            String author,
            String authorDisplayName,
            String latestVersion,
            String riskLevel,
            String status,
            Instant subscribedAt
    ) {}

    /**
     * 當前 user 訂閱指定 skill。idempotent — 重複訂閱 noop。
     *
     * @throws NoSuchElementException 找不到對應 skillId
     */
    public void subscribe(String skillId) {
        if (skillRepo.findById(skillId).isEmpty()) {
            throw new NoSuchElementException("Skill not found: " + skillId);
        }
        var subscriberId = users.userId();
        if (repo.existsBySkillIdAndSubscriberId(skillId, subscriberId)) {
            return; // idempotent
        }
        repo.save(SkillSubscription.create(skillId, subscriberId));
    }

    /**
     * 當前 user 取消訂閱指定 skill。idempotent — 找不到 row 安靜 return。
     */
    public void unsubscribe(String skillId) {
        var subscriberId = users.userId();
        repo.findBySkillIdAndSubscriberId(skillId, subscriberId)
                .ifPresent(repo::delete);
    }

    /**
     * 當前 user 是否已訂閱指定 skill — frontend SkillDetail subscribe button 狀態查詢。
     */
    @Transactional(readOnly = true)
    public boolean isSubscribed(String skillId) {
        return repo.existsBySkillIdAndSubscriberId(skillId, users.userId());
    }

    /**
     * 找指定 skill 的所有 subscriber（subscriber_id list）— S125b
     * NotificationProjectionListener.onVersionPublished 走此 lookup 寫 notification。
     */
    @Transactional(readOnly = true)
    public List<String> findSubscribersOf(String skillId) {
        return repo.findBySkillId(skillId).stream()
                .map(SkillSubscription::getSubscriberId)
                .toList();
    }

    /**
     * 找當前 user 訂閱的所有 skill（skillId list）— 預期 GET /me/subscriptions endpoint。
     */
    @Transactional(readOnly = true)
    public List<String> findSubscriptionsOfCurrentUser() {
        return repo.findBySubscriberId(users.userId()).stream()
                .map(SkillSubscription::getSkillId)
                .toList();
    }

    /**
     * 找當前 user 訂閱的 skill 摘要 — S145「我的技能 / 訂閱」tab 使用。
     */
    @Transactional(readOnly = true)
    public List<SkillSubscriptionSummary> findSubscriptionDetailsOfCurrentUser() {
        var params = new MapSqlParameterSource("subscriberId", users.userId());
        return jdbc.query("""
                SELECT ss.skill_id,
                       ss.created_at AS subscribed_at,
                       s.name AS skill_name,
                       s.author,
                       s.author_name_snapshot,
                       s.latest_version,
                       s.risk_level,
                       s.status,
                       u.name AS user_name,
                       u.email AS user_email,
                       u.handle AS user_handle
                FROM skill_subscriptions ss
                JOIN skills s ON s.id = ss.skill_id
                LEFT JOIN users u ON u.id = s.author
                WHERE ss.subscriber_id = :subscriberId
                ORDER BY ss.created_at DESC
                """, params, (rs, rowNum) -> {
            var author = rs.getString("author");
            var displayName = rs.getString("user_name") != null || rs.getString("user_email") != null
                    || rs.getString("user_handle") != null
                    ? DisplayNameResolver.resolve(
                            rs.getString("user_name"),
                            null,
                            null,
                            rs.getString("user_email"),
                            rs.getString("user_handle"),
                            author)
                    : firstNonBlank(rs.getString("author_name_snapshot"), author);
            var subscribedAt = rs.getTimestamp("subscribed_at").toInstant();
            return new SkillSubscriptionSummary(
                    rs.getString("skill_id"),
                    rs.getString("skill_name"),
                    author,
                    displayName,
                    rs.getString("latest_version"),
                    rs.getString("risk_level"),
                    rs.getString("status"),
                    subscribedAt);
        });
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
