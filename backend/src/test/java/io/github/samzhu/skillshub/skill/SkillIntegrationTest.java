package io.github.samzhu.skillshub.skill;

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
class SkillIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	@DisplayName("AC-2: 取得 Skill 詳情 — POST then GET returns consistent data")
	@SuppressWarnings("unchecked")
	void postThenGetSkill() {
		var command = new CreateSkillCommand("test-skill", "A test skill", "tester", "Testing");

		var postResponse = restTemplate.postForEntity("/api/v1/skills", command, Map.class);
		assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		var skillId = (String) postResponse.getBody().get("id");

		// S024 T05B: response 改為 Skill aggregate JSON — 用 Map 解析（Skill 無 @JsonCreator）
		var getResponse = restTemplate.getForEntity("/api/v1/skills/{id}", Map.class, skillId);
		assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		var skill = getResponse.getBody();
		assertThat(skill).isNotNull();
		assertThat(skill.get("id")).isEqualTo(skillId);
		assertThat(skill.get("name")).isEqualTo("test-skill");
		assertThat(skill.get("description")).isEqualTo("A test skill");
		assertThat(skill.get("author")).isEqualTo("tester");
		assertThat(skill.get("category")).isEqualTo("Testing");
		assertThat(skill.get("status")).isEqualTo("DRAFT");
		assertThat(skill.get("createdAt")).isNotNull();
	}

}
