package io.github.samzhu.skillshub.security.scan.detectors;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-T01 — semantic issue definition collected by LlmJudge into one structured-output prompt.
 *
 * @see SkillIssueCode
 */
public interface LlmIssueRule {
	SkillIssueCode issueCode();

	IssueCategory category();

	String rulePrompt();

	String positiveExample();

	String negativeExample();
}
