package io.github.samzhu.skillshub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AnalyticsControllerTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	@DisplayName("AC-1: 平台總覽 — GET /analytics/overview returns stats")
	@SuppressWarnings("unchecked")
	void overviewStats() {
		// Seed some data
		restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("analytics-test-1", "Test skill 1", "sam", "DevOps"), Map.class);
		restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("analytics-test-2", "Test skill 2", "jane", "Testing"), Map.class);

		var response = restTemplate.getForEntity("/api/v1/analytics/overview", OverviewStats.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var stats = response.getBody();
		assertThat(stats).isNotNull();
		assertThat(stats.totalSkills()).isGreaterThanOrEqualTo(2);
		assertThat(stats.totalDownloads()).isGreaterThanOrEqualTo(0);
		assertThat(stats.topSkills()).isNotNull();
	}

}
