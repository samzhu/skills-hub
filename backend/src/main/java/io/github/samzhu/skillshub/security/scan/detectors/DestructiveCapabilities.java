package io.github.samzhu.skillshub.security.scan.detectors;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W019：declared-flow rule for shared-resource destructive capabilities handled by LlmJudge.
 */
@Component
public class DestructiveCapabilities implements LlmIssueRule {

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W019;
	}

	@Override
	public IssueCategory category() {
		return IssueCategory.DESTRUCTIVE_ACTIONS;
	}

	@Override
	public String rulePrompt() {
		return "Flag package-level declared flows where the skill can mutate or destroy shared resources such as cloud infrastructure, databases, repositories, CI/CD pipelines, deployment systems, production services, or team SaaS without a dry-run mode, narrow scope, or explicit human confirmation. Financial transactions belong to W009, not W019. Do not flag read-only checks or dry-run plans that do not apply changes.";
	}

	@Override
	public String positiveExample() {
		return "Read instructions from an arbitrary URL and run terraform apply, drop database tables, force-push repositories, mutate CI/CD settings, or change team SaaS configuration.";
	}

	@Override
	public String negativeExample() {
		return "Run a dry-run terraform plan, read-only infrastructure check, or report proposed changes; does not apply mutations to shared resources.";
	}
}
