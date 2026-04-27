package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S014 AC-3 — Aggregate sequence UNIQUE constraint + replay 順序測試。
 *
 * <p>V1 schema 建立的 {@code idx_domain_events_aggregate_seq} 為 UNIQUE INDEX，
 * 在 PostgreSQL 端強制 {@code (aggregate_id, sequence)} 不可重複；違反時拋
 * {@link DataIntegrityViolationException}（Spring 把 PSQLException 轉換）。
 *
 * <p>取代既有 Mongo 上的「@CompoundIndex 不 unique」設計 — Mongo 版本沒有
 * 強制 UNIQUE，這是 S014 順手修的「靜默 race condition」。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DomainEventSequenceUniquenessTest {

	@Autowired
	private DomainEventRepository repo;

	@Test
	@DisplayName("AC-3: (aggregate_id, sequence) UNIQUE 約束阻擋重複 sequence")
	@Tag("AC-3")
	void uniqueConstraint_blocksDuplicateSequence() {
		var aggregateId = UUID.randomUUID().toString();
		repo.save(buildEvent(aggregateId, 1L));
		repo.save(buildEvent(aggregateId, 2L));

		// 嘗試插入 (aggregateId, sequence=2) 第二次
		assertThatThrownBy(() -> repo.save(buildEvent(aggregateId, 2L)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("AC-3: findByAggregateIdOrderBySequenceAsc 回傳 sequence 升冪排列")
	@Tag("AC-3")
	void replay_returnsEventsInSequenceOrder() {
		var aggregateId = UUID.randomUUID().toString();
		// 故意亂序寫入
		repo.save(buildEvent(aggregateId, 3L));
		repo.save(buildEvent(aggregateId, 1L));
		repo.save(buildEvent(aggregateId, 2L));

		var events = repo.findByAggregateIdOrderBySequenceAsc(aggregateId);
		assertThat(events).hasSize(3);
		assertThat(events).extracting(DomainEvent::sequence).containsExactly(1L, 2L, 3L);
	}

	@Test
	@DisplayName("AC-3: findTopByAggregateIdOrderBySequenceDesc 回傳 sequence 最大的事件")
	@Tag("AC-3")
	void latestSequence_returnsHighestSequenceEvent() {
		var aggregateId = UUID.randomUUID().toString();
		repo.save(buildEvent(aggregateId, 1L));
		repo.save(buildEvent(aggregateId, 5L));
		repo.save(buildEvent(aggregateId, 3L));

		var top = repo.findTopByAggregateIdOrderBySequenceDesc(aggregateId).orElseThrow();
		assertThat(top.sequence()).isEqualTo(5L);
	}

	private static DomainEvent buildEvent(String aggregateId, long sequence) {
		return new DomainEvent(
				UUID.randomUUID().toString(),
				aggregateId,
				"Skill",
				"SkillCreated",
				Map.<String, Object>of("name", "test", "sequence", sequence),
				sequence,
				Instant.now(),
				Map.of());
	}
}
