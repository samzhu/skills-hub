package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class ThirdPartyContentExposureTest {

	private final ThirdPartyContentExposure rule = new ThirdPartyContentExposure();

	@Test
	@DisplayName("AC-S147-W011: rule describes arbitrary third-party instructions as W011")
	@Tag("AC-S147-W011")
	void ruleDescribesArbitraryThirdPartyInstructionsAsW011() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W011);
		assertThat(rule.category()).isEqualTo(IssueCategory.EXTERNAL_CONTENT);
		assertThat(rule.rulePrompt()).contains("third-party", "user-generated", "instructions");
		assertThat(rule.positiveExample()).contains("URL");
	}

	@Test
	@DisplayName("AC-S147-W011: read-only documentation search is negative example")
	@Tag("AC-S147-W011")
	void readOnlyDocumentationSearchIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("read-only documentation");
		assertThat(rule.negativeExample()).contains("does not execute");
	}
}
