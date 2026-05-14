package io.github.samzhu.skillshub.security.scan.detectors;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecretPatternCatalog;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W008：detect hardcoded API keys, tokens, private keys, and credential-bearing URLs.
 */
@Component
public class HardcodedSecrets implements IssueDetector {

	private static final String MESSAGE = "Hardcoded secret detected";
	private static final String REMEDIATION = "請移除寫死在 package 文字裡的 secret，改由環境變數或 secret manager 在 runtime 注入。";

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W008;
	}

	@Override
	public Phase phase() {
		return Phase.STATIC;
	}

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		var findings = new ArrayList<SecurityFinding>();
		for (var entry : context.packageFiles().entrySet()) {
			scanFile(entry.getKey(), entry.getValue(), findings);
		}
		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	private void scanFile(String filePath, String content, List<SecurityFinding> sink) {
		for (SecretPatternCatalog.Match match : SecretPatternCatalog.scanFile(filePath, content)) {
			sink.add(finding(
					issueCode().defaultSeverity(),
					match.ruleId(),
					MESSAGE,
					REMEDIATION,
					Confidence.HIGH,
					match.filePath(),
					match.line(),
					match.maskedEvidence()));
		}
	}
}
