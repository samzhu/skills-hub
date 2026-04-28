package io.github.samzhu.skillshub.skill.command;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

// S016 T3：PUT /skills/{id}/versions 加 @PreAuthorize 後，anonymous TestRestTemplate
// 拿不到 ACL 通過權；切 LAB 模式並把 lab-user-id 對齊測試 fixture 的 author="sam"，
// 讓 LabSecurityFilter 注入的 principal 與 SkillProjection seed 的 user:sam:write
// pattern 一致 → @PreAuthorize 通過。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@org.springframework.test.context.TestPropertySource(properties = {
		"skillshub.security.oauth.enabled=false",
		"skillshub.security.lab.user-id=sam"
})
class SkillUploadTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private DomainEventRepository eventStore;

	@Test
	@DisplayName("AC-1: 上傳合法的純 markdown skill — POST /upload → 201 + events")
	@SuppressWarnings("unchecked")
	void uploadValidSkill() throws IOException {
		var zipBytes = createZipWithSkillMd("---\nname: test-upload\ndescription: A test skill\n---\n# Test");

		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zipBytes) {
			@Override public String getFilename() { return "test.zip"; }
		});
		body.add("version", "1.0.0");
		body.add("author", "sam");
		body.add("category", "DevOps");

		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		var response = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).containsKey("id");

		var skillId = (String) response.getBody().get("id");
		var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
		assertThat(events).hasSizeGreaterThanOrEqualTo(2);
		assertThat(events.get(0).eventType()).isEqualTo("SkillCreated");
		assertThat(events.get(0).payload().get("name")).isEqualTo("test-upload");
		assertThat(events.get(1).eventType()).isEqualTo("SkillVersionPublished");
		assertThat(events.get(1).payload().get("version")).isEqualTo("1.0.0");
	}

	@Test
	@DisplayName("AC-2: 上傳不合規的 skill — no SKILL.md → 400")
	@SuppressWarnings("unchecked")
	void uploadInvalidSkill() throws IOException {
		var zipBytes = createZipWithFile("README.md", "# Just a readme");
		long eventCountBefore = eventStore.count();

		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zipBytes) {
			@Override public String getFilename() { return "bad.zip"; }
		});
		body.add("version", "1.0.0");
		body.add("author", "sam");
		body.add("category", "DevOps");

		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		var response = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("error", "VALIDATION_ERROR");
		assertThat((String) response.getBody().get("message")).contains("SKILL.md not found");
		assertThat(eventStore.count()).isEqualTo(eventCountBefore);
	}

	@Test
	@DisplayName("AC-3: 更新已有 skill 的版本 — PUT /{id}/versions → 200")
	@SuppressWarnings("unchecked")
	void addVersionToExistingSkill() throws IOException {
		// Create skill first
		var zipV1 = createZipWithSkillMd("---\nname: versioned-skill\ndescription: Version test\n---\n# V1");
		var createBody = new LinkedMultiValueMap<String, Object>();
		createBody.add("file", new ByteArrayResource(zipV1) {
			@Override public String getFilename() { return "v1.zip"; }
		});
		createBody.add("version", "1.0.0");
		createBody.add("author", "sam");
		createBody.add("category", "DevOps");
		var createHeaders = new HttpHeaders();
		createHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
		var createResponse = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(createBody, createHeaders), Map.class);
		var skillId = (String) createResponse.getBody().get("id");

		// Add version 1.1.0
		var zipV2 = createZipWithSkillMd("---\nname: versioned-skill\ndescription: Version test updated\n---\n# V2");
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(zipV2) {
			@Override public String getFilename() { return "v2.zip"; }
		});
		body.add("version", "1.1.0");
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		var response = restTemplate.exchange(
				"/api/v1/skills/" + skillId + "/versions",
				org.springframework.http.HttpMethod.PUT,
				new HttpEntity<>(body, headers), Void.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
		var versionEvents = events.stream().filter(e -> "SkillVersionPublished".equals(e.eventType())).toList();
		assertThat(versionEvents).hasSize(2);
		assertThat(versionEvents.get(1).payload().get("version")).isEqualTo("1.1.0");
	}

	@Test
	@DisplayName("AC-4: 版本號重複 — PUT /{id}/versions → 409")
	@SuppressWarnings("unchecked")
	void duplicateVersionRejected() throws IOException {
		// Create skill with v1.0.0
		var zip = createZipWithSkillMd("---\nname: dup-version-skill\ndescription: Dup test\n---\n# Test");
		var createBody = new LinkedMultiValueMap<String, Object>();
		createBody.add("file", new ByteArrayResource(zip) {
			@Override public String getFilename() { return "init.zip"; }
		});
		createBody.add("version", "1.0.0");
		createBody.add("author", "sam");
		createBody.add("category", "DevOps");
		var createHeaders = new HttpHeaders();
		createHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
		var createResponse = restTemplate.postForEntity(
				"/api/v1/skills/upload", new HttpEntity<>(createBody, createHeaders), Map.class);
		var skillId = (String) createResponse.getBody().get("id");
		long eventCountAfterCreate = eventStore.findByAggregateIdOrderBySequenceAsc(skillId).size();

		// Try to add duplicate v1.0.0
		var dupZip = createZipWithSkillMd("---\nname: dup-version-skill\ndescription: Dup test\n---\n# Dup");
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(dupZip) {
			@Override public String getFilename() { return "dup.zip"; }
		});
		body.add("version", "1.0.0");
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		var response = restTemplate.exchange(
				"/api/v1/skills/" + skillId + "/versions",
				org.springframework.http.HttpMethod.PUT,
				new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("error", "VERSION_EXISTS");
		assertThat(eventStore.findByAggregateIdOrderBySequenceAsc(skillId)).hasSize((int) eventCountAfterCreate);
	}

	private byte[] createZipWithSkillMd(String content) throws IOException {
		return createZipWithFile("SKILL.md", content);
	}

	private byte[] createZipWithFile(String filename, String content) throws IOException {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			zos.putNextEntry(new ZipEntry(filename));
			zos.write(content.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return baos.toByteArray();
	}

}
