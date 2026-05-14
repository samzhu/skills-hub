package io.github.samzhu.skillshub.security.scan.detectors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W012：detect mutable remote content that controls runtime instructions or executable code.
 */
@Component
public class UnverifiableExternalDependency implements IssueDetector {

	private static final String MESSAGE = "Mutable remote dependency controls runtime behavior";
	private static final String REMEDIATION = "請改用固定版本與可驗證 checksum/signature；不要在 runtime 從可變 URL 載入 prompt、instructions 或 executable code。";

	private static final Pattern URL = Pattern.compile("https?://[^\\s\"')]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern MUTABLE_URL = Pattern.compile("(?i)https?://[^\\s\"')]*(?:latest|main|master|HEAD)[^\\s\"')]*");
	private static final Pattern RUNTIME_PROMPT = Pattern.compile("(?i)(?:runtime|each\\s+run|每次|執行時|load|fetch|download|retrieve|載入|抓|讀取).{0,120}(?:instructions?|prompt|system\\s+prompt|指令|提示)");
	private static final Pattern REMOTE_SCRIPT_SOURCE = Pattern.compile("(?i)\\bsource\\s+<\\(\\s*(?:curl|wget)\\b[^)]*https?://");
	private static final Pattern VERSIONED_RELEASE = Pattern.compile("(?i)github\\.com/[^/]+/[^/]+/releases/download/v?\\d+\\.\\d+\\.\\d+/");
	private static final Pattern CHECKSUM = Pattern.compile("(?i)\\b(?:sha256|sha512|checksum|sig|signature|gpg)\\b");

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W012;
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
		if (content == null || content.isEmpty() || isVersionedAndVerified(content)) {
			return;
		}
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i].strip();
			if (REMOTE_SCRIPT_SOURCE.matcher(line).find()) {
				sink.add(toFinding("W012_REMOTE_SCRIPT_SOURCE", filePath, i + 1, line));
			} else if (URL.matcher(line).find()
					&& MUTABLE_URL.matcher(line).find()
					&& RUNTIME_PROMPT.matcher(line).find()) {
				sink.add(toFinding("W012_REMOTE_PROMPT", filePath, i + 1, line));
			}
		}
	}

	private boolean isVersionedAndVerified(String content) {
		return VERSIONED_RELEASE.matcher(content).find() && CHECKSUM.matcher(content).find();
	}

	private SecurityFinding toFinding(String ruleId, String filePath, Integer line, String evidence) {
		return finding(
				issueCode().defaultSeverity(),
				ruleId,
				MESSAGE,
				REMEDIATION,
				Confidence.HIGH,
				filePath,
				line,
				evidence);
	}
}
