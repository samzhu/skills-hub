package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class LocalDestructiveCapabilitiesTest {

	private final LocalDestructiveCapabilities rule = new LocalDestructiveCapabilities();

	@Test
	@DisplayName("AC-S147-W020: rule describes local destructive actions as W020")
	@Tag("AC-S147-W020")
	void ruleDescribesLocalDestructiveActionsAsW020() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W020);
		assertThat(rule.category()).isEqualTo(IssueCategory.DESTRUCTIVE_ACTIONS);
		assertThat(rule.rulePrompt()).contains("local files", "settings", "workspace");
		assertThat(rule.positiveExample()).contains("rm -rf");
	}

	@Test
	@DisplayName("AC-S147-W020: explicit output directory write is negative example")
	@Tag("AC-S147-W020")
	void explicitOutputDirectoryWriteIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("output directory");
		assertThat(rule.negativeExample()).contains("does not delete");
	}
}
