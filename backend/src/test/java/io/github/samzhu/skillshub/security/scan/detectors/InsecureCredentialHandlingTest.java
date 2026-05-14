package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class InsecureCredentialHandlingTest {

	private final InsecureCredentialHandling rule = new InsecureCredentialHandling();

	@Test
	@DisplayName("AC-S147-W007: rule describes credential disclosure as W007")
	@Tag("AC-S147-W007")
	void ruleDescribesCredentialDisclosureAsW007() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W007);
		assertThat(rule.category()).isEqualTo(IssueCategory.CREDENTIALS);
		assertThat(rule.rulePrompt()).contains("print", "log", "paste", "external");
		assertThat(rule.positiveExample()).contains("API token");
	}

	@Test
	@DisplayName("AC-S147-W007: local env var use without disclosure is negative example")
	@Tag("AC-S147-W007")
	void localEnvVarUseWithoutDisclosureIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("env", "local");
		assertThat(rule.negativeExample()).doesNotContain("print raw");
	}
}
