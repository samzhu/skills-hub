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
 * S147-E005：detect suspicious external downloads that are executed by the package.
 */
@Component
public class SuspiciousDownloadUrl implements IssueDetector {

	private static final String MESSAGE = "Suspicious download URL executed by package";
	private static final String REMEDIATION = "請移除短網址或 IP 來源的下載執行流程，改用可驗證來源與固定版本，並提供 checksum 或簽章驗證。";

	private static final Pattern DOWNLOAD_COMMAND = Pattern.compile("(?i)\\b(?:curl|wget)\\b");
	private static final Pattern EXECUTE_SIGNAL = Pattern.compile("(?i)(?:\\|\\s*(?:bash|sh)\\b|chmod\\s+\\+x|/tmp/[A-Za-z0-9._-]+|\\b(?:bash|sh)\\s+/tmp/)");
	private static final Pattern SHORTENER_URL = Pattern.compile("https?://(?:bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|is\\.gd|ow\\.ly)/[^\\s\"')]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern IP_URL = Pattern.compile("https?://(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?/[^\\s\"')]+", Pattern.CASE_INSENSITIVE);

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.E005;
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
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i].strip();
			if (!DOWNLOAD_COMMAND.matcher(line).find() || !EXECUTE_SIGNAL.matcher(line).find()) {
				continue;
			}
			if (SHORTENER_URL.matcher(line).find()) {
				sink.add(toFinding("E005_SHORTENER_DOWNLOAD_EXECUTE", filePath, i + 1, line));
			} else if (IP_URL.matcher(line).find()) {
				sink.add(toFinding("E005_IP_BINARY_DOWNLOAD", filePath, i + 1, line));
			}
		}
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
