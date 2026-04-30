package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SkillSearchTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private SkillRepository skillRepo;

	@BeforeEach
	void setUp() {
		skillRepo.deleteAll();
		// Seed test data via POST API (triggers event → projection → read model)
		restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("docker-helper", "Docker compose helper", "sam", "DevOps"), Map.class);
		restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("k8s-deploy", "Kubernetes deployment skill", "jane", "DevOps"), Map.class);
		restTemplate.postForEntity("/api/v1/skills",
				new CreateSkillCommand("test-runner", "Run unit tests automatically", "bob", "Testing"), Map.class);
	}

	@Test
	@DisplayName("AC-1: 用關鍵字搜尋技能 — keyword=docker returns matching skills")
	@SuppressWarnings("unchecked")
	void keywordSearch() {
		var response = restTemplate.getForEntity("/api/v1/skills?keyword=docker", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var content = (List<Map<String, Object>>) response.getBody().get("content");
		assertThat(content).hasSize(1);
		assertThat(content.getFirst().get("name")).isEqualTo("docker-helper");
	}

	@Test
	@DisplayName("AC-2: 按分類篩選技能 — category=DevOps returns 2 skills")
	@SuppressWarnings("unchecked")
	void categoryFilter() {
		var response = restTemplate.getForEntity("/api/v1/skills?category=DevOps", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var content = (List<Map<String, Object>>) response.getBody().get("content");
		assertThat(content).hasSize(2);
		assertThat(content).allMatch(skill -> "DevOps".equals(skill.get("category")));
	}

	@Test
	@DisplayName("AC-3: 關鍵字 + 分類組合篩選 — keyword=deploy&category=DevOps")
	@SuppressWarnings("unchecked")
	void keywordAndCategoryCombo() {
		var response = restTemplate.getForEntity("/api/v1/skills?keyword=deploy&category=DevOps", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var content = (List<Map<String, Object>>) response.getBody().get("content");
		assertThat(content).hasSize(1);
		assertThat(content.getFirst().get("name")).isEqualTo("k8s-deploy");
	}

	@Test
	@DisplayName("AC-4: 分類列表 API — returns categories with counts")
	void categoriesList() {
		var response = restTemplate.exchange(
				"/api/v1/categories", HttpMethod.GET, null,
				new ParameterizedTypeReference<List<CategoryCount>>() {});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var categories = response.getBody();
		assertThat(categories).isNotEmpty();
		assertThat(categories).anyMatch(c -> "DevOps".equals(c.name()) && c.count() == 2);
		assertThat(categories).anyMatch(c -> "Testing".equals(c.name()) && c.count() == 1);
	}

}
