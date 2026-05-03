package io.github.samzhu.skillshub.community;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/**
 * S125a — SkillSubscription repo。
 *
 * <p>Derived query 全部 by Spring Data JDBC convention：
 * <ul>
 *   <li>{@code findBySkillIdAndSubscriberId} — service layer subscribe 前檢查重複；unsubscribe 找 row 刪除</li>
 *   <li>{@code existsBySkillIdAndSubscriberId} — idempotent subscribe 路徑（避 SELECT 全 row）</li>
 *   <li>{@code findBySkillId} — S125b NotificationProjectionListener.onVersionPublished
 *       訂閱者 lookup（每個 subscriber 寫一條 notification）</li>
 *   <li>{@code findBySubscriberId} — 預期 future "我的訂閱" 頁面 / GET /me/subscriptions endpoint</li>
 * </ul>
 *
 * <p><b>Index</b>：V14 migration 建 (skill_id) + (subscriber_id) 兩 index 對應上述兩 lookup path。
 */
public interface SkillSubscriptionRepository extends CrudRepository<SkillSubscription, String> {

    Optional<SkillSubscription> findBySkillIdAndSubscriberId(String skillId, String subscriberId);

    boolean existsBySkillIdAndSubscriberId(String skillId, String subscriberId);

    List<SkillSubscription> findBySkillId(String skillId);

    List<SkillSubscription> findBySubscriberId(String subscriberId);
}
