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
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SkillDownloadTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private DomainEventRepository eventStore;

	@Autowired
	private SkillRepository skillRepo;

	@Test
	@DisplayName("AC-1: 下載最新版本 — GET /download → 200 + zip + SkillDownloaded audit event")
	@SuppressWarnings("unchecked")
	void downloadLatestVersion() throws IOException {
		var skillId = uploadSkill("download-test", "1.0.0");

		var response = restTemplate.getForEntity("/api/v1/skills/" + skillId + "/download", byte[].class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().length).isGreaterThan(0);

		// S024 T05B: AuditEventListener async 寫 SkillDownloaded audit row + Skill aggregate downloadCount sync 增量
		org.awaitility.Awaitility.await()
				.atMost(java.time.Duration.ofSeconds(30))
				.untilAsserted(() -> {
					var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
					assertThat(events).anyMatch(e -> "SkillDownloaded".equals(e.eventType()));
					var skill = skillRepo.findById(skillId).orElseThrow();
					assertThat(skill.getDownloadCount()).isGreaterThanOrEqualTo(1);
				});
	}

	@Test
	@DisplayName("AC-2: 下載指定版本 — GET /versions/{ver}/download → 200")
	@SuppressWarnings("unchecked")
	void downloadSpecificVersion() throws IOException {
		var skillId = uploadSkill("version-dl-test", "1.0.0");

		var response = restTemplate.getForEntity(
				"/api/v1/skills/" + skillId + "/versions/1.0.0/download", byte[].class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
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
