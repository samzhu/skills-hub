package io.github.samzhu.skillshub.review.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

/**
 * S098e2 — Review aggregate 的 Spring Data JDBC repository。
 *
 * <p>{@code save()} 經 Spring Data {@code EventPublishingRepositoryProxyPostProcessor}
 * 攔截，自動 publish {@code AbstractAggregateRoot.domainEvents()} 至 Modulith outbox（同 TX）。
 *
 * <p>業務查詢：
 * <ul>
 *   <li>{@link #findBySkillIdOrderByCreatedAtDesc} — AC-8 列表時序 desc</li>
 *   <li>{@link #existsBySkillIdAndAuthorId} — AC-4 1-per-user 檢查</li>
 * </ul>
 */
public interface ReviewRepository extends ListCrudRepository<Review, String> {

    List<Review> findBySkillIdOrderByCreatedAtDesc(String skillId);

    boolean existsBySkillIdAndAuthorId(String skillId, String authorId);

    Optional<Review> findById(String id);
}
