package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.query.SkillReadModelRepository;
import io.github.samzhu.skillshub.skill.query.SkillVersionReadModelRepository;

/**
 * S010 多引擎安全掃描 e2e 整合測試。
 *
 * <p>S014 從 MongoTemplate 讀取改 Spring Data JDBC repository — read model 結構不變，
 * 只是底層儲存從 Firestore（MongoDB driver）換成 PostgreSQL。SARIF / findings / notices
 * JSONB 欄位透過 {@code JdbcConfiguration} 的 Map↔JSONB Converter 還原為
 * {@code Map<String, Object>}（nested List/Map 結構保留）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RiskAssessmentIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private SkillReadModelRepository skillRepo;

	@Autowired
	private SkillVersionReadModelRepository versionRepo;

	@Autowired
	private DomainEventRepository eventStore;

	@Test
	@DisplayName("AC-1: 純 markdown skill → risk level LOW")
	@Tag("AC-1")
	void safeSkillGetsLowRisk() throws IOException {
		var zip = createZip(Map.of("SKILL.md", "---\nname: safe-skill\ndescription: No scripts\n---\n# Safe"));

		var skillId = uploadSkill(zip);

		var skill = skillRepo.findById(skillId).orElseThrow();
		assertThat(skill.riskLevel()).isEqualTo("LOW");
	}

	@Test
	@DisplayName("AC-1: 含危險指令的 scripts → risk level HIGH")
	@Tag("AC-1")
	void dangerousScriptGetsHighRisk() throws IOException {
		var zip = createZip(Map.of(
				"SKILL.md", "---\nname: danger-skill\ndescription: Has scripts\n---\n# Danger",
				"scripts/setup.sh", "#!/bin/bash\necho hello\nrm -rf /tmp/data\n"
		));

		var skillId = uploadSkill(zip);

		var skill = skillRepo.findById(skillId).orElseThrow();
		assertThat(skill.riskLevel()).isEqualTo("HIGH");
	}

	@Test
	@DisplayName("AC-1 e2e + AC-8: multi-engine pipeline 寫入完整 SARIF + findings 至 read model")
	@Tag("AC-1")
	@Tag("AC-8")
	@SuppressWarnings("unchecked")
	void multiEngineFindingsCoverage() throws IOException {
		// 1 個 skill 含：合法 frontmatter + 1 個 dangerous-command 腳本 + 1 個 secret 腳本
		// 啟用引擎：pattern, secret, metadata, meta（test 環境 llm.enabled=false，bean 不建立）
		var zip = createZip(Map.of(
				"SKILL.md", "---\nname: malicious-skill\ndescription: Multi-engine target\n---\n# Test",
				"scripts/setup.sh", "#!/bin/bash\necho hi\nrm -rf /home\n",
				"scripts/deploy.sh", "#!/bin/bash\nexport GH_TOKEN=ghp_1234567890abcdef1234567890abcdef1234\n"
		));

		var skillId = uploadSkill(zip);

		// AC-1 e2e: domain_events 含 SkillCreated + SkillVersionPublished + SkillRiskAssessed
		// 用 Awaitility 等候 ScanOrchestrator 的 @EventListener 同步完成（synchronous，但 multipart upload
		// 在不同 thread 會有微小延遲）
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
			assertThat(events).extracting(e -> e.eventType())
					.contains("SkillCreated", "SkillVersionPublished", "SkillRiskAssessed");
		});

		// AC-8.2: skills.risk_level = HIGH
		var skill = skillRepo.findById(skillId).orElseThrow();
		assertThat(skill.riskLevel()).isEqualTo("HIGH");

		// AC-8.1: skill_versions.{version}.risk_assessment 結構驗證
		var version = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId).stream()
				.filter(v -> "1.0.0".equals(v.version()))
				.findFirst().orElseThrow();
		var riskAssessment = version.riskAssessment();
		assertThat(riskAssessment)
				.as("skill_versions.risk_assessment must be populated by ScanOrchestrator")
				.isNotNull();

		assertThat(riskAssessment.get("level")).isEqualTo("HIGH");

		// findings 至少 2 筆（pattern: rm -rf + secret: ghp_...）— JSONB 還原為 List<Map>
		var findings = (List<Map<String, Object>>) riskAssessment.get("findings");
		assertThat(findings).hasSizeGreaterThanOrEqualTo(2);
		assertThat(findings).extracting(d -> (String) d.get("analyzer"))
				.contains("pattern", "secret");
		assertThat(findings).extracting(d -> (String) d.get("ruleId"))
				.anyMatch(s -> s.startsWith("DANGEROUS_COMMAND") || s.startsWith("PIPE_TO_SHELL"));
		assertThat(findings).extracting(d -> (String) d.get("ruleId"))
				.contains("GITHUB_PAT");

		// SARIF 結構合規 — JSONB 還原為 Map<String, Object>
		var sarif = (Map<String, Object>) riskAssessment.get("sarif");
		assertThat(sarif).isNotNull();
		assertThat(sarif.get("version")).isEqualTo("2.1.0");
		assertThat(sarif.get("$schema"))
				.isEqualTo("https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.json");

		// runs[]：每個啟用引擎一個 run。LLM 關閉 → 4 個（pattern, secret, metadata, meta）
		var runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat(runs).hasSize(4);
		assertThat(runs)
				.extracting(r -> ((Map<String, Object>) r.get("tool")).get("driver"))
				.extracting(d -> (String) ((Map<String, Object>) d).get("name"))
				.containsExactlyInAnyOrder("pattern", "secret", "metadata", "meta");

		// scannedAt 是合法時間戳（JSONB 序列化為 ISO-8601 字串）
		assertThat(riskAssessment.get("scannedAt"))
				.as("scannedAt must be a valid timestamp").isNotNull();
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
