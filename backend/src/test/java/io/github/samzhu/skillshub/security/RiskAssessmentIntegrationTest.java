package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.query.SkillReadModel;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RiskAssessmentIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Test
	@DisplayName("AC-1: 純 markdown skill → risk level LOW")
	@SuppressWarnings("unchecked")
	void safeSkillGetsLowRisk() throws IOException {
		var zip = createZip(Map.of("SKILL.md", "---\nname: safe-skill\ndescription: No scripts\n---\n# Safe"));

		var skillId = uploadSkill(zip);

		var skill = mongoTemplate.findById(skillId, SkillReadModel.class, "skills");
		assertThat(skill).isNotNull();
		assertThat(skill.riskLevel()).isEqualTo("LOW");
	}

	@Test
	@DisplayName("AC-2: 含危險指令的 scripts → risk level HIGH")
	@SuppressWarnings("unchecked")
	void dangerousScriptGetsHighRisk() throws IOException {
		var zip = createZip(Map.of(
				"SKILL.md", "---\nname: danger-skill\ndescription: Has scripts\n---\n# Danger",
				"scripts/setup.sh", "#!/bin/bash\necho hello\nrm -rf /tmp/data\n"
		));

		var skillId = uploadSkill(zip);

		var skill = mongoTemplate.findById(skillId, SkillReadModel.class, "skills");
		assertThat(skill).isNotNull();
		assertThat(skill.riskLevel()).isEqualTo("HIGH");
	}

	@SuppressWarnings("unchecked")
	private String uploadSkill(byte[] zip) {
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zip) {
			@Override public String getFilename() { return "skill.zip"; }
		});
		body.add("version", "1.0.0");
		body.add("author", "tester");
		body.add("category", "Testing");
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		var response = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return (String) response.getBody().get("id");
	}

	private byte[] createZip(Map<String, String> files) throws IOException {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			for (var entry : files.entrySet()) {
				zos.putNextEntry(new ZipEntry(entry.getKey()));
				zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
		}
		return baos.toByteArray();
	}

}
