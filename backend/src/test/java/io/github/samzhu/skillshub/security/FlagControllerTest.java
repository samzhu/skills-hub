package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class FlagControllerTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private DomainEventRepository eventStore;

	@Test
	@DisplayName("AC-4: 社群回報 — POST /flags → 201 + SkillFlagged event")
	@SuppressWarnings("unchecked")
	void createFlag() {
		// Create a skill first
		var createResponse = restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("flaggable-skill", "A skill to flag", "sam", "Testing"), Map.class);
		var skillId = (String) createResponse.getBody().get("id");

		// Submit flag
		var flagRequest = Map.of("type", "SECURITY", "description", "可疑的外部連線");
		var response = restTemplate.postForEntity(
				"/api/v1/skills/" + skillId + "/flags", flagRequest, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).containsKey("id");

		// Verify event in store
		var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
		assertThat(events).anyMatch(e -> "SkillFlagged".equals(e.eventType()));

		// Verify flag in read model
		var flagsResponse = restTemplate.exchange(
				"/api/v1/skills/" + skillId + "/flags",
				HttpMethod.GET, null,
				new ParameterizedTypeReference<List<FlagReadModel>>() {});
		assertThat(flagsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(flagsResponse.getBody()).hasSize(1);
		assertThat(flagsResponse.getBody().getFirst().type()).isEqualTo("SECURITY");
		assertThat(flagsResponse.getBody().getFirst().status()).isEqualTo("OPEN");
	}

}
