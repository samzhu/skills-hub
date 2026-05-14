package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class SensitiveDataExposureTest {

	private final SensitiveDataExposure rule = new SensitiveDataExposure();

	@Test
	@DisplayName("AC-S147-W017: rule describes sensitive data external exposure as W017")
	@Tag("AC-S147-W017")
	void ruleDescribesSensitiveDataExternalExposureAsW017() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W017);
		assertThat(rule.category()).isEqualTo(IssueCategory.SENSITIVE_DATA);
		assertThat(rule.rulePrompt()).contains("sensitive data", "external", "webhook");
		assertThat(rule.positiveExample()).contains("email");
	}

	@Test
	@DisplayName("AC-S147-W017: local redaction flow is negative example")
	@Tag("AC-S147-W017")
	void localRedactionFlowIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("local redaction");
		assertThat(rule.negativeExample()).contains("does not send");
	}
}
