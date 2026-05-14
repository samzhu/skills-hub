package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.Severity;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class MissingSkillManifestTest {

	private final MissingSkillManifest detector = new MissingSkillManifest();

	@Test
	@DisplayName("AC-S147-W014: package without root SKILL.md reports W014")
	@Tag("AC-S147-W014")
	void packageWithoutRootSkillMdReportsW014() {
		var output = detector.analyze(ctx(List.of("README.md", "scripts/setup.sh")));

		assertThat(output.findings()).hasSize(1);
		var finding = output.findings().getFirst();
		assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W014);
		assertThat(finding.ruleId()).isEqualTo("W014_MISSING_SKILL_MD");
		assertThat(finding.severity()).isEqualTo(Severity.LOW);
		assertThat(finding.remediation()).contains("SKILL.md");
		assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
		assertThat(finding.filePath()).isNull();
		assertThat(finding.line()).isNull();
		assertThat(detector.category()).isEqualTo(IssueCategory.PACKAGE_STRUCTURE);
	}

	@Test
	@DisplayName("AC-S147-W014: nested SKILL.md without root SKILL.md reports W014")
	@Tag("AC-S147-W014")
	void nestedSkillMdOnlyReportsW014() {
		var output = detector.analyze(ctx(List.of("nested/SKILL.md", "nested/scripts/setup.sh")));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W014));
	}

	@Test
	@DisplayName("AC-S147-W014: root SKILL.md package reports no finding")
	@Tag("AC-S147-W014")
	void rootSkillMdReportsNoFinding() {
		var output = detector.analyze(ctx(List.of("SKILL.md", "scripts/setup.sh")));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("MissingSkillManifest implements IssueDetector contract")
	void implementsIssueDetectorContract() {
		assertThat(detector.issueCode()).isEqualTo(SkillIssueCode.W014);
		assertThat(detector.name()).isEqualTo("W014");
		assertThat(detector.phase()).isEqualTo(Phase.STATIC);
	}

	private static ScanContext ctx(List<String> packagePaths) {
		return new ScanContext(
				"skill-1",
				"1.0.0",
				Map.of("name", "demo"),
				"# Demo",
				Map.of(),
				packagePaths,
				List.of());
	}
}
