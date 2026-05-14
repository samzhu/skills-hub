package io.github.samzhu.skillshub.security.scan.detectors;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W017：declared-flow rule for sensitive data exposure handled by LlmJudge.
 */
@Component
public class SensitiveDataExposure implements LlmIssueRule {

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W017;
	}

	@Override
	public IssueCategory category() {
		return IssueCategory.SENSITIVE_DATA;
	}

	@Override
	public String rulePrompt() {
		return "Flag package-level declared flows where the skill reads sensitive data such as PII, email, DMs, credentials, credential vaults, tokens, financial records, or private user data and sends raw content, summaries, tokens, or derived sensitive details to an external endpoint, webhook, third-party API, or untrusted service. The finding should state that the package declares this flow, not that runtime access was proven. Do not flag local redaction, local-only analysis, or user-visible output when the sensitive data is not sent externally.";
	}

	@Override
	public String positiveExample() {
		return "Read email or DMs and send the raw text or summary to a webhook, or read a credential vault and send tokens to a third-party debug endpoint.";
	}

	@Override
	public String negativeExample() {
		return "Perform local redaction of PII and return only masked output to the user; does not send raw or summarized sensitive data externally.";
	}
}
