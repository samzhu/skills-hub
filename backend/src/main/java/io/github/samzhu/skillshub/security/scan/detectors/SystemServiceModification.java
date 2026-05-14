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
 * S147-W013：detect scripts that modify system services, startup hooks, or global machine settings.
 */
@Component
public class SystemServiceModification implements IssueDetector {

	private static final String MESSAGE = "System service or global startup configuration modification";
	private static final String REMEDIATION = "請移除修改 system service、cron、sudoers、shell 啟動檔或全域設定的指令；若必要，改成明確文件步驟讓使用者手動確認。";

	private static final List<Rule> RULES = List.of(
			new Rule("W013_SYSTEMD_MODIFICATION",
					Pattern.compile("(?i)\\bsystemctl\\s+(?:enable|disable|start|restart|daemon-reload|link)\\b|/etc/systemd/")),
			new Rule("W013_LAUNCH_DAEMON",
					Pattern.compile("(?i)(?:/Library/Launch(?:Agents|Daemons)/|~/Library/LaunchAgents/).*\\.plist")),
			new Rule("W013_CRON_PERSISTENCE",
					Pattern.compile("(?i)\\bcrontab\\b|/etc/cron\\.|/etc/cron(?:\\.d|\\.daily|\\.hourly|\\.monthly|\\.weekly)?/|@reboot")),
			new Rule("W013_SUDOERS_MODIFICATION",
					Pattern.compile("(?i)/etc/sudoers(?:\\.d)?|\\bvisudo\\b")),
			new Rule("W013_SHELL_STARTUP_MODIFICATION",
					Pattern.compile("(?i)(?:~|\\$HOME)/\\.(?:bashrc|zshrc|profile|bash_profile)|/etc/(?:profile|bashrc|zshrc)"))
	);

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W013;
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
			for (Rule rule : RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(toFinding(rule.ruleId(), filePath, i + 1, line));
				}
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

	private record Rule(String ruleId, Pattern pattern) {}
}
