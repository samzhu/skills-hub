package io.github.samzhu.skillshub.security.scan.detectors;

import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-T01 — static issue-code detector contract that still plugs into ScanOrchestrator as a SecurityAnalyzer.
 *
 * @see SecurityAnalyzer
 * @see SkillIssueCode
 */
public interface IssueDetector extends SecurityAnalyzer {
	SkillIssueCode issueCode();

	default IssueCategory category() {
		return issueCode().category();
	}

	@Override
	default String name() {
		return issueCode().code();
	}

	default SecurityFinding finding(
			Severity severity,
			String ruleId,
			String message,
			String remediation,
			Confidence confidence,
			String filePath,
			Integer line,
			String evidence) {
		return new SecurityFinding(
				ruleId,
				issueCode(),
				severity,
				message,
				remediation,
				confidence,
				filePath,
				line,
				evidence,
				name(),
				null);
	}
}
