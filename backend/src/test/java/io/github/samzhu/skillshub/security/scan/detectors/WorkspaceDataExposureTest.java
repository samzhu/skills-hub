package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class WorkspaceDataExposureTest {

	private final WorkspaceDataExposure rule = new WorkspaceDataExposure();

	@Test
	@DisplayName("AC-S147-W018: rule describes workspace data external exposure as W018")
	@Tag("AC-S147-W018")
	void ruleDescribesWorkspaceDataExternalExposureAsW018() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W018);
		assertThat(rule.category()).isEqualTo(IssueCategory.SENSITIVE_DATA);
		assertThat(rule.rulePrompt()).contains("workspace", "source code", "external");
		assertThat(rule.positiveExample()).contains("repository");
	}

	@Test
	@DisplayName("AC-S147-W018: local code review is negative example")
	@Tag("AC-S147-W018")
	void localCodeReviewIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("local code review");
		assertThat(rule.negativeExample()).contains("does not send");
	}
}
