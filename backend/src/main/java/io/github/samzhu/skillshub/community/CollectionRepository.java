package io.github.samzhu.skillshub.community;

import java.util.List;

import org.springframework.data.repository.ListCrudRepository;

/**
 * S096f2 — Collection aggregate Spring Data JDBC repo。
 *
 * <p>{@code save()} 透過 Spring Data {@code EventPublishingRepositoryProxyPostProcessor}
 * 攔截器自動 publish {@link Collection#domainEvents()} 至 Modulith outbox（同 TX）—
 * 對齊 Request/Skill aggregate 既驗。
 *
 * <p>List sort 走 single-property `created_at DESC`（無 compound sort）— 故 derived
 * query naming 安全，不需 RequestRepository / NotificationRepository 走的 `@Query`
 * AOT compound-sort workaround。
 */
public interface CollectionRepository extends ListCrudRepository<Collection, String> {

    /** S096f2 AC-5 — 全 collection 列表，按 createdAt desc。 */
    List<Collection> findAllByOrderByCreatedAtDesc();

    /** S096f2 AC-5 — category filter；caller 端確認 category 非 null/blank 才走此 path。 */
    List<Collection> findAllByCategoryOrderByCreatedAtDesc(String category);
}
