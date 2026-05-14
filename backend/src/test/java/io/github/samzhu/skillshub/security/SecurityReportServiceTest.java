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

	private static SecurityReportService serviceWith(SkillVersion version) {
		var repo = mock(SkillVersionRepository.class);
		when(repo.findBySkillIdOrderByPublishedAtDesc("skill-1")).thenReturn(List.of(version));
		return new SecurityReportService(repo, new SecurityCategoryMapper(), JsonMapper.builder().build());
	}

	private static SkillVersion versionWithRisk(Map<String, Object> riskAssessment) {
		var version = mock(SkillVersion.class);
		when(version.getId()).thenReturn("version-1");
		when(version.getSkillId()).thenReturn("skill-1");
		when(version.getVersion()).thenReturn("1.0.0");
		when(version.getPublishedAt()).thenReturn(Instant.parse("2026-05-14T00:00:00Z"));
		when(version.getRiskAssessment()).thenReturn(riskAssessment);
		return version;
	}
}
