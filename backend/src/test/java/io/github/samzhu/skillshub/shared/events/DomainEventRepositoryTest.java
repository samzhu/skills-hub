package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DomainEventRepositoryTest {

	@Autowired
	private DomainEventRepository repository;

	@Test
	@DisplayName("AC-4: Event Store 基礎設施可用 — 寫入並讀回 domain event")
	void shouldPersistAndRetrieveDomainEvent() {
		var aggregateId = UUID.randomUUID().toString();
		var event = new DomainEvent(
				UUID.randomUUID().toString(),
				aggregateId,
				"Skill",
				"SkillCreated",
				Map.of("name", "docker-helper", "author", "sam"),
				1L,
				Instant.now(),
				Map.of()
		);

		repository.save(event);

		var events = repository.findByAggregateIdOrderBySequenceAsc(aggregateId);
		assertThat(events).hasSize(1);

		var saved = events.getFirst();
		assertThat(saved.aggregateId()).isEqualTo(aggregateId);
		assertThat(saved.aggregateType()).isEqualTo("Skill");
		assertThat(saved.eventType()).isEqualTo("SkillCreated");
		assertThat(saved.payload()).containsEntry("name", "docker-helper");
		assertThat(saved.payload()).containsEntry("author", "sam");
		assertThat(saved.sequence()).isEqualTo(1L);
		assertThat(saved.occurredAt()).isNotNull();
	}

}
