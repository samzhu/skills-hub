package io.github.samzhu.skillshub.security.scan.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

class DirectFinancialExecutionTest {

	private final DirectFinancialExecution rule = new DirectFinancialExecution();

	@Test
	@DisplayName("AC-S147-W009: rule describes direct financial execution as W009")
	@Tag("AC-S147-W009")
	void ruleDescribesDirectFinancialExecutionAsW009() {
		assertThat(rule.issueCode()).isEqualTo(SkillIssueCode.W009);
		assertThat(rule.category()).isEqualTo(IssueCategory.FINANCIAL_ACTIONS);
		assertThat(rule.rulePrompt()).contains("market order", "transfer", "withdraw", "crypto");
		assertThat(rule.positiveExample()).contains("market buy order");
	}

	@Test
	@DisplayName("AC-S147-W009: portfolio analysis is negative example")
	@Tag("AC-S147-W009")
	void portfolioAnalysisIsNegativeExample() {
		assertThat(rule.negativeExample()).contains("portfolio analysis");
		assertThat(rule.negativeExample()).contains("does not place");
	}
}
