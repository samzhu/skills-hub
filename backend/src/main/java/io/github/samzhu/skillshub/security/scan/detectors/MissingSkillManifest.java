package io.github.samzhu.skillshub.security.scan.detectors;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W014：package root 沒有 SKILL.md 時回報 issue-code finding。
 */
@Component
public class MissingSkillManifest implements IssueDetector {

	static final String RULE_ID = "W014_MISSING_SKILL_MD";
	private static final String MESSAGE = "Package root is missing SKILL.md";
	private static final String REMEDIATION = "請在 skill package 根目錄補 SKILL.md，並把 name/description frontmatter 寫在檔案開頭。";

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W014;
	}

	@Override
	public Phase phase() {
		return Phase.STATIC;
	}

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		if (context.packagePaths() != null && context.packagePaths().contains("SKILL.md")) {
			return AnalysisOutput.empty();
		}
		SecurityFinding finding = finding(
				issueCode().defaultSeverity(),
				RULE_ID,
				MESSAGE,
				REMEDIATION,
				Confidence.HIGH,
				null,
				null,
				null);
		return new AnalysisOutput(List.of(finding), List.<ScanNotice>of());
	}
}
