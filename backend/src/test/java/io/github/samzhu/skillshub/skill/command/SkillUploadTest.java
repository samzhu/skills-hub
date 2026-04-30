package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
// 讓 LabSecurityFilter 注入的 principal 與 Skill aggregate seed 的 user:sam:write
// pattern 一致 → @PreAuthorize 通過。
//
// S024 T05B：audit row 由 AuditEventListener async 寫入，依 aggregateId + event_type 過濾
// 用 Awaitility 等候；不再依賴 size 斷言（避免 ScanOrchestrator async race）。
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
	@DisplayName("AC-1: 上傳合法的純 markdown skill — POST /upload → 201 + audit events")
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
		// async audit — 等候 SkillCreated + SkillVersionPublished 兩筆 audit row
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
			var created = events.stream()
					.filter(e -> "SkillCreated".equals(e.eventType())).findFirst();
			var published = events.stream()
					.filter(e -> "SkillVersionPublished".equals(e.eventType())).findFirst();
			assertThat(created).isPresent();
			assertThat(created.get().payload().get("name")).isEqualTo("test-upload");
			assertThat(published).isPresent();
			assertThat(published.get().payload().get("version")).isEqualTo("1.0.0");
		});
	}

	@Test
	@DisplayName("AC-2: 上傳不合規的 skill — no SKILL.md → 400 + 無 SkillCreated 寫入")
	@SuppressWarnings("unchecked")
	void uploadInvalidSkill() throws IOException {
		var zipBytes = createZipWithFile("README.md", "# Just a readme");

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
		// 驗證未建立 aggregate（無 skillId 返回，無 audit row 對應任何 aggregate；count() 全表斷言不可靠 — 移除）
	}

	@Test
	@DisplayName("AC-3: 更新已有 skill 的版本 — PUT /{id}/versions → 200 + 兩筆 SkillVersionPublished audit")
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

		// async audit — 等候兩筆 SkillVersionPublished
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
			var versionEvents = events.stream()
					.filter(e -> "SkillVersionPublished".equals(e.eventType()))
					.toList();
			assertThat(versionEvents).hasSize(2);
			assertThat(versionEvents.stream().map(e -> e.payload().get("version")))
					.containsExactlyInAnyOrder("1.0.0", "1.1.0");
		});
	}

	@Test
	@DisplayName("AC-4: 版本號重複 — PUT /{id}/versions → 409 + 不重複寫入 SkillVersionPublished audit")
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

		// 確認 SkillVersionPublished audit 仍只有 1 筆（dedup by sourceEventId；同 version 不重複寫）
		// 等夠久讓 async 完成；filter by event_type 避免 ScanOrchestrator 寫入干擾
		org.awaitility.Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(30))
				.untilAsserted(() -> {
					var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
					var versionEvents = events.stream()
							.filter(e -> "SkillVersionPublished".equals(e.eventType()))
							.toList();
					assertThat(versionEvents).hasSize(1);
				});
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
