package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SkillCommandServiceTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private SkillCommandService commandService;

	@Autowired
	private DomainEventRepository eventStore;

	@Test
	@DisplayName("AC-1: 建立新 Skill — POST /api/v1/skills → 201 + SkillCreated event in store")
	void createSkillViaApi() {
		var command = new CreateSkillCommand("docker-helper", "Docker compose helper", "sam", "DevOps");

		var response = restTemplate.postForEntity("/api/v1/skills", command, java.util.Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).containsKey("id");

		var skillId = (String) response.getBody().get("id");
		// S024 T05B: AuditEventListener async — Awaitility 等
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
			assertThat(events).hasSize(1);

			var event = events.getFirst();
			assertThat(event.aggregateType()).isEqualTo("Skill");
			assertThat(event.eventType()).isEqualTo("SkillCreated");
			assertThat(event.payload()).containsEntry("name", "docker-helper");
			assertThat(event.payload()).containsEntry("description", "Docker compose helper");
			assertThat(event.payload()).containsEntry("author", "sam");
			assertThat(event.payload()).containsEntry("category", "DevOps");
			assertThat(event.sequence()).isEqualTo(1L);
		});
	}

	@Test
	@DisplayName("AC-5: Event Store 完整性 — create + publishVersion → 2 events ordered by sequence")
	void eventStoreIntegrity() {
		var skillId = commandService.createSkill(
				new CreateSkillCommand("k8s-deploy", "K8s deployment skill", "jane", "DevOps"));

		commandService.publishVersion(
				new PublishVersionCommand(skillId, "1.0.0", "gs://bucket/k8s-deploy/1.0.0.zip", 0, java.util.Map.of()));

		// S024 T05B: 等候 SkillCreated + SkillVersionPublished 兩筆 audit row（async）；
		// 不依賴 sequence 順序（可能 SkillStateAdvancedToPublished 加進 audit）
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
			var types = events.stream().map(e -> e.eventType()).toList();
			assertThat(types).contains("SkillCreated", "SkillVersionPublished");
		});
	}

}
