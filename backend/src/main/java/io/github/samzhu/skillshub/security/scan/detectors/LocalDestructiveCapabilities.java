package io.github.samzhu.skillshub.security.scan.detectors;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W020：declared-flow rule for local destructive capabilities handled by LlmJudge.
 */
@Component
public class LocalDestructiveCapabilities implements LlmIssueRule {

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W020;
	}

	@Override
	public IssueCategory category() {
		return IssueCategory.DESTRUCTIVE_ACTIONS;
	}

	@Override
	public String rulePrompt() {
		return "Flag package-level declared flows where the skill deletes, overwrites, renames, or destructively modifies local files, local settings, shell/editor configuration, workspace state, project files, or user data without a narrow path allowlist or explicit confirmation step. Do not flag writing generated output to an explicit output directory or creating new reports when the flow does not delete or overwrite existing local state.";
	}

	@Override
	public String positiveExample() {
		return "Run rm -rf, delete matching project files, overwrite shell settings, or modify editor configuration without an allowlist or confirmation.";
	}

	@Override
	public String negativeExample() {
		return "Write a generated report to an explicit output directory such as ./out/; does not delete or overwrite existing local files or settings.";
	}
}
