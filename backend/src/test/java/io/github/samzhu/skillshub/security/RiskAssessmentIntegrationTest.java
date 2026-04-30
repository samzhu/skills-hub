package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.util.LinkedMultiValueMap;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S010 多引擎安全掃描 e2e 整合測試 / S025a-T02 rewrite — 3 個 disabled method 恢復 + Awaitility → Scenario。
 *
 * <p>對應 spec §3 AC-1 + AC-8 — multi-engine pipeline 寫入完整 SARIF + findings。
 *
 * <p><b>S025a-T02 migration</b>（per S023 §7.7 揭露 timing race band-aid）：
 * <ul>
 *   <li>annotation 維持 {@code @SpringBootTest(WebEnvironment.RANDOM_PORT)} — 保留 HTTP e2e
 *       coverage（TestRestTemplate 上傳 zip 經 Spring controller 完整 stack）；不切換
 *       {@code @ApplicationModuleTest} 因該模式無 webserver，會失去 HTTP 路徑驗證</li>
 *   <li>移除 {@code Awaitility.await(30s).untilAsserted(...)}；改用 {@link Scenario}
 *       {@code stimulate(() -> uploadSkill(...))
 *       .andWaitForStateChange(() -> 等 domain_events 含 SkillRiskAssessed audit row)}
 *       — 此 sync point 同時捕捉 ScanOrchestrator pipeline 完成 + AuditEventListener
 *       async 寫 audit row，比直接等 SkillRiskAssessedEvent 更可靠（後者 event 到達時
 *       audit row 可能還在 async listener queue）</li>
 *   <li>3 個 method 移除 {@code @Disabled} annotation — S023-T07 timing race 由 Scenario
 *       的 thread-bound listener adapter + ScenarioCustomizer 5s default 解</li>
 *   <li>每個 wait 顯式 {@code .andWaitAtMost(Duration.ofSeconds(15))} override
 *       global 5s default — ScanOrchestrator 4 個 engine 平行 + SARIF 大型 JSONB；
 *       保守設定，T05 量測 p95 latency 後評估收回 5s default（spec §3 AC-10）</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.TestcontainersConfiguration#scenarioTimeout
 * @see <a href="https://raw.githubusercontent.com/spring-projects/spring-modulith/2.0.6/spring-modulith-test/src/main/java/org/springframework/modulith/test/Scenario.java">Spring Modulith Scenario API</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@EnableScenarios
class RiskAssessmentIntegrationTest {

	private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(15);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private SkillRepository skillRepo;

	@Autowired
	private SkillVersionRepository versionRepo;

	@Autowired
	private DomainEventRepository eventStore;

	@Test
	@DisplayName("AC-1: 純 markdown skill → risk level LOW")
	@Tag("AC-1")
	void safeSkillGetsLowRisk(Scenario scenario) throws IOException {
		var zip = createZip(Map.of("SKILL.md", "---\nname: safe-skill\ndescription: No scripts\n---\n# Safe"));
		var skillIdRef = new AtomicReference<String>();

		// stimulate 包裝 HTTP upload；wait 等 audit log 出現 SkillRiskAssessed row（同時捕捉
		// ScanOrchestrator pipeline 完成 + AuditEventListener 寫 audit row 雙重 async sync point）。
		scenario.stimulate(() -> skillIdRef.set(uploadSkill(zip)))
				.andWaitAtMost(SCAN_TIMEOUT)
				.andWaitForStateChange(() -> riskAssessedAuditRowOrNull(skillIdRef.get()))
				.andVerify(row -> {
					var skill = skillRepo.findById(skillIdRef.get()).orElseThrow();
					assertThat(skill.getRiskLevel()).isEqualTo("LOW");
				});
	}

	@Test
	@DisplayName("AC-1: 含危險指令的 scripts → risk level HIGH")
	@Tag("AC-1")
	void dangerousScriptGetsHighRisk(Scenario scenario) throws IOException {
		var zip = createZip(Map.of(
				"SKILL.md", "---\nname: danger-skill\ndescription: Has scripts\n---\n# Danger",
				"scripts/setup.sh", "#!/bin/bash\necho hello\nrm -rf /tmp/data\n"
		));
		var skillIdRef = new AtomicReference<String>();

		scenario.stimulate(() -> skillIdRef.set(uploadSkill(zip)))
				.andWaitAtMost(SCAN_TIMEOUT)
				.andWaitForStateChange(() -> riskAssessedAuditRowOrNull(skillIdRef.get()))
				.andVerify(row -> {
					var skill = skillRepo.findById(skillIdRef.get()).orElseThrow();
					assertThat(skill.getRiskLevel()).isEqualTo("HIGH");
				});
	}

	@Test
	@DisplayName("AC-1 e2e + AC-8: multi-engine pipeline 寫入完整 SARIF + findings 至 read model")
	@Tag("AC-1")
	@Tag("AC-8")
	@SuppressWarnings("unchecked")
	void multiEngineFindingsCoverage(Scenario scenario) throws IOException {
		// 1 個 skill 含：合法 frontmatter + 1 個 dangerous-command 腳本 + 1 個 secret 腳本
		// 啟用引擎：pattern, secret, metadata, meta（test 環境 llm.enabled=false，bean 不建立）
		var zip = createZip(Map.of(
				"SKILL.md", "---\nname: malicious-skill\ndescription: Multi-engine target\n---\n# Test",
				"scripts/setup.sh", "#!/bin/bash\necho hi\nrm -rf /home\n",
				"scripts/deploy.sh", "#!/bin/bash\nexport GH_TOKEN=ghp_1234567890abcdef1234567890abcdef1234\n"
		));
		var skillIdRef = new AtomicReference<String>();

		scenario.stimulate(() -> skillIdRef.set(uploadSkill(zip)))
				.andWaitAtMost(SCAN_TIMEOUT)
				.andWaitForStateChange(() -> riskAssessedAuditRowOrNull(skillIdRef.get()))
				.andVerify(row -> {
					// AC-1 e2e: domain_events 含 SkillCreated + SkillVersionPublished + SkillRiskAssessed
					var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillIdRef.get());
					assertThat(events).extracting(e -> e.eventType())
							.contains("SkillCreated", "SkillVersionPublished", "SkillRiskAssessed");

					// AC-8.2: skills.risk_level = HIGH
					var skill = skillRepo.findById(skillIdRef.get()).orElseThrow();
					assertThat(skill.getRiskLevel()).isEqualTo("HIGH");

					// AC-8.1: skill_versions.{version}.risk_assessment 結構驗證
					var version = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillIdRef.get()).stream()
							.filter(v -> "1.0.0".equals(v.getVersion()))
							.findFirst().orElseThrow();
					var riskAssessment = version.getRiskAssessment();
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
				});
	}

	/**
	 * 等 ScanOrchestrator pipeline 完成（async）+ AuditEventListener 寫 audit row（async）的雙重 sync point。
	 * SkillRiskAssessed audit row 出現代表：(1) ScanOrchestrator persist() 完成 →
	 * (2) SkillRiskAssessedEvent published → (3) AuditEventListener async 寫 domain_events row。
	 */
	private io.github.samzhu.skillshub.shared.events.DomainEvent riskAssessedAuditRowOrNull(String skillId) {
		if (skillId == null) {
			return null;
		}
		return eventStore.findByAggregateIdOrderBySequenceAsc(skillId).stream()
				.filter(e -> "SkillRiskAssessed".equals(e.eventType()))
				.findFirst()
				.orElse(null);
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
