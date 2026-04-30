package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

// S016 T3：PUT /skills/{id}/versions 加 @PreAuthorize 後 anonymous 不過 — 同
// SkillUploadTest 處理：LAB mode + lab-user-id 對齊 author="tester"。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@org.springframework.test.context.TestPropertySource(properties = {
		"skillshub.security.oauth.enabled=false",
		"skillshub.security.lab.user-id=tester"
})
class SkillVersionQueryTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	@DisplayName("AC-5: 取得版本歷史 — GET /skills/{id}/versions returns sorted versions")
	void getVersionHistory() throws IOException {
		// Upload skill with v1.0.0
		var skillId = uploadSkill("version-query-skill", "1.0.0");

		// Add v1.1.0
		addVersion(skillId, "1.1.0");

		// Query versions — Skill aggregate API exposes via getter naming（version, storagePath, fileSize）
		var response = restTemplate.exchange(
				"/api/v1/skills/" + skillId + "/versions",
				HttpMethod.GET, null,
				new ParameterizedTypeReference<List<Map<String, Object>>>() {});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var versions = response.getBody();
		assertThat(versions).hasSize(2);
		// Sorted by publishedAt DESC — newest first
		assertThat(versions.get(0).get("version")).isEqualTo("1.1.0");
		assertThat(versions.get(1).get("version")).isEqualTo("1.0.0");
		assertThat((String) versions.get(0).get("storagePath")).contains(skillId);
		assertThat(((Number) versions.get(0).get("fileSize")).longValue()).isGreaterThan(0);
	}

	@SuppressWarnings("unchecked")
	private String uploadSkill(String name, String version) throws IOException {
		var zip = createZip("---\nname: " + name + "\ndescription: Test\n---\n# " + name);
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zip) {
			@Override public String getFilename() { return "skill.zip"; }
		});
		body.add("version", version);
		body.add("author", "tester");
		body.add("category", "Testing");
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		var response = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(body, headers), Map.class);
		return (String) response.getBody().get("id");
	}

	private void addVersion(String skillId, String version) throws IOException {
		var zip = createZip("---\nname: skill\ndescription: Updated\n---\n# V" + version);
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zip) {
			@Override public String getFilename() { return "v.zip"; }
		});
		body.add("version", version);
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		restTemplate.exchange("/api/v1/skills/" + skillId + "/versions",
				HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
	}

	private byte[] createZip(String skillMdContent) throws IOException {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			zos.putNextEntry(new ZipEntry("SKILL.md"));
			zos.write(skillMdContent.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return baos.toByteArray();
	}

}
