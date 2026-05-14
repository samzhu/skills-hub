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

class SystemServiceModificationTest {

	private final SystemServiceModification detector = new SystemServiceModification();

	@Test
	@DisplayName("AC-S147-W013: system service modification reports W013")
	@Tag("AC-S147-W013")
	void systemServiceModificationReportsW013() {
		var script = "sudo systemctl enable skill-agent.service";
		var output = detector.analyze(ctx(Map.of("scripts/install.sh", script)));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> {
					assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W013);
					assertThat(finding.ruleId()).isEqualTo("W013_SYSTEMD_MODIFICATION");
					assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
					assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
					assertThat(finding.filePath()).isEqualTo("scripts/install.sh");
				});
	}

	@Test
	@DisplayName("AC-S147-W013: LaunchDaemon write reports W013")
	@Tag("AC-S147-W013")
	void launchDaemonWriteReportsW013() {
		var script = "cat plist > /Library/LaunchDaemons/com.acme.skill.plist";
		var output = detector.analyze(ctx(Map.of("scripts/install-macos.sh", script)));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("W013_LAUNCH_DAEMON"));
	}

	@Test
	@DisplayName("AC-S147-W013: cron persistence reports W013")
	@Tag("AC-S147-W013")
	void cronPersistenceReportsW013() {
		var script = "(crontab -l; echo '@reboot /tmp/skill-agent') | crontab -";
		var output = detector.analyze(ctx(Map.of("scripts/persist.sh", script)));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("W013_CRON_PERSISTENCE"));
	}

	@Test
	@DisplayName("AC-S147-W013: local generated file write does not report W013")
	@Tag("AC-S147-W013")
	void localGeneratedFileWriteDoesNotReportW013() {
		var script = "mkdir -p ./out && echo '# report' > ./out/report.md";
		var output = detector.analyze(ctx(Map.of("scripts/report.sh", script)));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: system service instruction in references file reports W013")
	@Tag("AC-S147-PACKAGE-FILES")
	void systemServiceInstructionInReferencesFileReportsW013() {
		var output = detector.analyze(ctxFiles(Map.of(
				"references/setup.md", "Run sudo systemctl enable skill-agent.service")));

		assertThat(output.findings()).singleElement()
				.satisfies(finding -> {
					assertThat(finding.issueCode()).isEqualTo(SkillIssueCode.W013);
					assertThat(finding.filePath()).isEqualTo("references/setup.md");
				});
	}

	@Test
	@DisplayName("SystemServiceModification implements IssueDetector contract")
	void implementsIssueDetectorContract() {
		assertThat(detector.issueCode()).isEqualTo(SkillIssueCode.W013);
		assertThat(detector.name()).isEqualTo("W013");
		assertThat(detector.phase()).isEqualTo(Phase.STATIC);
	}

	private static ScanContext ctx(Map<String, String> scripts) {
		return new ScanContext("skill-1", "1.0.0", Map.of("name", "demo"),
				"# Demo", scripts, List.of("SKILL.md"), List.of());
	}

	private static ScanContext ctxFiles(Map<String, String> packageFiles) {
		return new ScanContext("skill-1", "1.0.0", Map.of("name", "demo"),
				"# Demo", Map.of(), packageFiles, List.copyOf(packageFiles.keySet()), List.of());
	}
}
