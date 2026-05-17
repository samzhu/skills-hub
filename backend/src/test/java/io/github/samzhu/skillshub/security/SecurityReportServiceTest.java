package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import tools.jackson.databind.json.JsonMapper;

class SecurityReportServiceTest {

	@Test
	@DisplayName("AC-S147-1: legacy risk JSON still returns checks")
	void legacyRiskJsonStillReturnsChecks() {
		var service = serviceWith(versionWithRisk(Map.of(
				"scannedAt", "2026-05-14T00:00:00Z",
				"findings", List.of(Map.of(
						"ruleId", "GITHUB_PAT",
						"severity", "HIGH",
						"message", "Hardcoded GitHub PAT",
						"filePath", "scripts/install.sh",
						"line", 14,
						"analyzer", "secret")))));

		var report = service.getReport("skill-1", null);

		assertThat(report.checks()).containsKeys("shell", "paths", "secrets", "deps");
		assertThat(report.checks().get("secrets").status()).isEqualTo("FAIL");
	}

	@Test
	@DisplayName("AC-S147-1: issue-code findings return categories and finding summaries")
	void issueCodeFindingsReturnCategoriesAndSummaries() {
		var service = serviceWith(versionWithRisk(Map.of(
				"scannedAt", "2026-05-14T00:00:00Z",
				"findings", List.of(Map.of(
						"ruleId", "W007_REVEAL_TOKEN",
						"issueCode", "W007",
						"severity", "HIGH",
						"message", "Skill asks the agent to print raw API tokens.",
						"remediation", "Never print, log, or paste raw credentials.",
						"confidence", "HIGH",
						"filePath", "SKILL.md",
						"line", 9,
						"evidence", "print API key",
						"analyzer", "llm-judge")))));

		var report = service.getReport("skill-1", null);

		assertThat(report.categories()).anySatisfy(category -> {
			assertThat(category.key()).isEqualTo("credentials");
			assertThat(category.label()).isEqualTo("Credentials");
			assertThat(category.status()).isEqualTo("FAIL");
			assertThat(category.findingCount()).isEqualTo(1);
			assertThat(category.highestSeverity()).isEqualTo("HIGH");
		});
		assertThat(report.findings()).singleElement().satisfies(finding -> {
			assertThat(finding.issueCode()).isEqualTo("W007");
			assertThat(finding.ruleId()).isEqualTo("W007_REVEAL_TOKEN");
			assertThat(finding.remediation()).isEqualTo("Never print, log, or paste raw credentials.");
			assertThat(finding.confidence()).isEqualTo("HIGH");
			assertThat(finding.filePath()).isEqualTo("SKILL.md");
			assertThat(finding.line()).isEqualTo(9);
			assertThat(finding.evidence()).isEqualTo("print API key");
		});
	}

	@Test
	@DisplayName("AC-S190-5: risk_assessment.riskReasons → GET security report contains riskReasons")
	void persistedRiskReasonsReturnInReport() {
		var service = serviceWith(versionWithRisk(Map.of(
				"scannedAt", "2026-05-17T00:00:00Z",
				"findings", List.of(),
				"riskReasons", List.of(Map.of(
						"code", "ALLOWED_TOOLS_DECLARED",
						"label", "這個技能可以要求 AI 使用工具",
						"detail", "掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用工具：Bash、Write，所以使用前請先確認你接受這些能力。",
						"impact", "LOW",
						"evidence", List.of("Bash", "Write"),
						"action", "REVIEW_FIRST")))));

		var report = service.getReport("skill-1", null);

		assertThat(report.findings()).isEmpty();
		assertThat(report.riskReasons()).singleElement().satisfies(reason -> {
			assertThat(reason.code()).isEqualTo("ALLOWED_TOOLS_DECLARED");
			assertThat(reason.label()).isEqualTo("這個技能可以要求 AI 使用工具");
			assertThat(reason.detail()).contains("掃描沒有找到需要修改的問題");
			assertThat(reason.impact()).isEqualTo("LOW");
			assertThat(reason.evidence()).containsExactly("Bash", "Write");
			assertThat(reason.action()).isEqualTo("REVIEW_FIRST");
		});
	}

	@Test
	@DisplayName("AC-S190-6: legacy LOW report without riskReasons uses SkillVersion.allowedTools")
	void legacyLowReportWithoutRiskReasonsUsesAllowedTools() {
		var service = serviceWith(versionWithRiskAndAllowedTools(Map.of(
				"scannedAt", "2026-05-17T00:00:00Z",
				"findings", List.of()), List.of("Bash", "Write")));

		var report = service.getReport("skill-1", null);

		assertThat(report.riskReasons()).singleElement().satisfies(reason -> {
			assertThat(reason.code()).isEqualTo("LEGACY_ALLOWED_TOOLS");
			assertThat(reason.label()).isEqualTo("這個技能可以要求 AI 使用工具");
			assertThat(reason.detail()).contains("這個技能可以要求 AI 使用工具");
			assertThat(reason.evidence()).containsExactly("Bash", "Write");
			assertThat(reason.action()).isEqualTo("REVIEW_FIRST");
		});
	}

	@Test
	@DisplayName("AC-S190-3: legacy NONE report without capabilities returns no-findings reason")
	void legacyNoneReportWithoutCapabilitiesReturnsNoFindingsReason() {
		var service = serviceWith(versionWithRiskAndAllowedTools(Map.of(
				"scannedAt", "2026-05-17T00:00:00Z",
				"findings", List.of()), List.of()));

		var report = service.getReport("skill-1", null);

		assertThat(report.riskReasons()).singleElement().satisfies(reason -> {
			assertThat(reason.code()).isEqualTo("NO_FINDINGS_NO_CAPABILITIES");
			assertThat(reason.label()).isEqualTo("沒有工具宣告或 scripts/");
			assertThat(reason.detail()).contains("未發現安全問題");
			assertThat(reason.impact()).isEqualTo("NONE");
			assertThat(reason.evidence()).isEmpty();
			assertThat(reason.action()).isEqualTo("DOWNLOAD_OK");
		});
	}

	private static SecurityReportService serviceWith(SkillVersion version) {
		var repo = mock(SkillVersionRepository.class);
		when(repo.findBySkillIdOrderByPublishedAtDesc("skill-1")).thenReturn(List.of(version));
		return new SecurityReportService(repo, new SecurityCategoryMapper(), JsonMapper.builder().build());
	}

	private static SkillVersion versionWithRisk(Map<String, Object> riskAssessment) {
		return versionWithRiskAndAllowedTools(riskAssessment, List.of());
	}

	private static SkillVersion versionWithRiskAndAllowedTools(
			Map<String, Object> riskAssessment, List<String> allowedTools) {
		var version = mock(SkillVersion.class);
		when(version.getId()).thenReturn("version-1");
		when(version.getSkillId()).thenReturn("skill-1");
		when(version.getVersion()).thenReturn("1.0.0");
		when(version.getPublishedAt()).thenReturn(Instant.parse("2026-05-14T00:00:00Z"));
		when(version.getRiskAssessment()).thenReturn(riskAssessment);
		when(version.getAllowedTools()).thenReturn(allowedTools);
		return version;
	}
}
