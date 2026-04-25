package io.github.samzhu.skillshub.shared.events;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 領域事件倉儲介面。
 *
 * <p>繼承 {@link MongoRepository} 提供基本 CRUD，並額外定義事件溯源所需的查詢方法，
 * 以便依聚合根 ID 重放歷史事件或取得最新序號。
 */
public interface DomainEventRepository extends MongoRepository<DomainEvent, String> {

	/**
	 * 依聚合根 ID 取得所有事件，按序號由小到大排序。
	 *
	 * <p>用於事件重放（replay），以正確順序還原聚合根狀態。
	 *
	 * @param aggregateId 聚合根識別碼
	 * @return 事件清單（序號升冪排列）
	 */
	List<DomainEvent> findByAggregateIdOrderBySequenceAsc(String aggregateId);

	/**
	 * 取得指定聚合根的最新一筆事件（序號最大）。
	 *
	 * <p>用於決定下一個事件的序號，實現樂觀鎖控制。
	 *
	 * @param aggregateId 聚合根識別碼
	 * @return 最新事件，若不存在則回傳 {@link Optional#empty()}
	 */
	Optional<DomainEvent> findTopByAggregateIdOrderBySequenceDesc(String aggregateId);

}
