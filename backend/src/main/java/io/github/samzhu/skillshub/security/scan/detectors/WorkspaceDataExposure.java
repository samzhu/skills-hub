package io.github.samzhu.skillshub.security.scan.detectors;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W018：declared-flow rule for workspace data exposure handled by LlmJudge.
 */
@Component
public class WorkspaceDataExposure implements LlmIssueRule {

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W018;
	}

	@Override
	public IssueCategory category() {
		return IssueCategory.SENSITIVE_DATA;
	}

	@Override
	public String rulePrompt() {
		return "Flag package-level declared flows where the skill reads workspace files, repository source code, local notes, project artifacts, local documents, or other user workspace data and sends raw content, file contents, summaries, or extracted details to an external API, third-party chatbot, webhook, or untrusted service. The finding should state that the package declares this exposure, not that runtime access was proven. Do not flag local code review or local summarization that only returns results to the user and does not send workspace data externally.";
	}

	@Override
	public String positiveExample() {
		return "Read repository source files and send them to an arbitrary API, or read local notes and send them to a third-party chat endpoint.";
	}

	@Override
	public String negativeExample() {
		return "Perform local code review and summarize findings to the user; does not send source, notes, or workspace data to an external service.";
	}
}
