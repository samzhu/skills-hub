package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class DestructiveCapabilitiesTest {

	private final DestructiveCapabilities rule = new DestructiveCapabilities();

	@Test
	@DisplayName("AC-S147-W019: rule describes shared destructive actions as W019")
	@Tag("AC-S147-W019")
	void ruleDescribesSharedDestructiveActionsAsW019() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W019);
		assertThat(rule.category()).isEqualTo(IssueCategory.DESTRUCTIVE_ACTIONS);
		assertThat(rule.rulePrompt()).contains("cloud infrastructure", "databases", "repositories", "CI/CD");
		assertThat(rule.positiveExample()).contains("terraform apply");
	}

	@Test
	@DisplayName("AC-S147-W019: dry-run infrastructure plan is negative example")
	@Tag("AC-S147-W019")
	void dryRunInfrastructurePlanIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("dry-run");
		assertThat(rule.negativeExample()).contains("does not apply");
	}
}
