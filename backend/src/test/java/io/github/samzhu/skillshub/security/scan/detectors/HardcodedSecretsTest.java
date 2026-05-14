package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.Severity;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class HardcodedSecretsTest {

	private final HardcodedSecrets detector = new HardcodedSecrets();

	@Test
	@DisplayName("AC-S147-W008: hardcoded secret reports W008 with masked evidence")
	@Tag("AC-S147-W008")
	void hardcodedSecretReportsW008WithMaskedEvidence() {
		var secret = "sk-" + "a".repeat(48);
		var output = detector.analyze(ctx("", Map.of("scripts/use-openai.sh", "OPENAI_API_KEY=" + secret)));

		assertThat(output.findings()).isNotEmpty();
		var finding = output.findings().stream()
				.filter(f -> "OPENAI_KEY".equals(f.ruleId()))
				.findFirst()
				.orElseThrow();

		assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W008);
		assertThat(finding.severity()).isEqualTo(Severity.HIGH);
		assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
		assertThat(finding.filePath()).isEqualTo("scripts/use-openai.sh");
		assertThat(finding.evidence()).contains("…");
		assertThat(finding.evidence()).doesNotContain(secret);
		assertThat(finding.message()).doesNotContain(secret);
		assertThat(finding.remediation()).doesNotContain(secret);
	}

	@Test
	@DisplayName("AC-S147-W008: db URL password reports W008 with masked evidence")
	@Tag("AC-S147-W008")
	void dbUrlPasswordReportsW008WithMaskedEvidence() {
		var dbUrl = "postgresql://user:secret123@host/db";
		var output = detector.analyze(ctx("", Map.of("scripts/migrate.sh", "DATABASE_URL=" + dbUrl)));

		var finding = output.findings().stream()
				.filter(f -> "DB_CONN_WITH_PASSWORD".equals(f.ruleId()))
				.findFirst()
				.orElseThrow();

		assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W008);
		assertThat(finding.evidence()).doesNotContain(dbUrl);
		assertThat(finding.evidence()).doesNotContain("secret123");
		assertThat(finding.message()).doesNotContain(dbUrl);
		assertThat(finding.remediation()).doesNotContain(dbUrl);
	}

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: hardcoded secret in references file reports W008")
	@Tag("AC-S147-PACKAGE-FILES")
	void hardcodedSecretInReferencesFileReportsW008() {
		var secret = "sk-" + "b".repeat(48);
		var output = detector.analyze(ctxFiles(Map.of("references/config.md", "OPENAI_API_KEY=" + secret)));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> {
					assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W008);
					assertThat(finding.filePath()).isEqualTo("references/config.md");
				});
	}

	@Test
	@DisplayName("AC-S147-W008: private key block reports W008 with masked evidence")
	@Tag("AC-S147-W008")
	void privateKeyBlockReportsW008WithMaskedEvidence() {
		var output = detector.analyze(ctx("-----BEGIN RSA PRIVATE KEY-----\nabc\n", Map.of()));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> {
					assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W008);
					assertThat(finding.ruleId()).isEqualTo("PEM_PRIVATE_KEY");
					assertThat(finding.evidence()).doesNotContain("-----BEGIN RSA PRIVATE KEY-----");
				});
	}

	@Test
	@DisplayName("AC-S147-W008: placeholder API key does not report W008")
	@Tag("AC-S147-W008")
	void placeholderApiKeyDoesNotReportW008() {
		var output = detector.analyze(ctx("", Map.of("scripts/example.sh", "api_key=\"YOUR_API_KEY_HERE\"")));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("HardcodedSecrets implements IssueDetector contract")
	void implementsIssueDetectorContract() {
		assertThat(detector.issueCode()).isEqualTo(SkillIssueCode.W008);
		assertThat(detector.name()).isEqualTo("W008");
		assertThat(detector.phase()).isEqualTo(Phase.STATIC);
	}

	private static ScanContext ctx(String skillMd, Map<String, String> scripts) {
		return new ScanContext("skill-1", "1.0.0", Map.of("name", "demo"),
				skillMd, scripts, List.of("SKILL.md"), List.of());
	}

	private static ScanContext ctxFiles(Map<String, String> packageFiles) {
		return new ScanContext("skill-1", "1.0.0", Map.of("name", "demo"),
				"# Demo", Map.of(), packageFiles, List.copyOf(packageFiles.keySet()), List.of());
	}
}
