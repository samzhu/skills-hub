package io.github.samzhu.skillshub.security.scan.detectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-E006：detect multi-signal malicious code patterns in package text files.
 */
@Component("malicious-code-patterns")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.malicious-code-patterns.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class MaliciousCodePatterns implements IssueDetector {

	private static final String MULTI_SIGNAL_RULE = "E006_MULTI_SIGNAL_MALICIOUS_CODE";
	private static final String OBFUSCATED_EXECUTION_RULE = "E006_OBFUSCATED_EXECUTION";
	private static final String MULTI_SIGNAL_MESSAGE =
			"Script combines sensitive data access with obfuscation or external execution";
	private static final String OBFUSCATED_EXECUTION_MESSAGE =
			"Script decodes content and executes it dynamically";
	private static final String REMEDIATION =
			"Remove credential/data reads from executable scripts, avoid decoded dynamic execution, and do not send local data to external endpoints.";

	private static final List<Rule> RULES = List.of(
			new Rule(Signal.SENSITIVE_READ, Pattern.compile("\\bcat\\s+\\.env\\b")),
			new Rule(Signal.SENSITIVE_READ, Pattern.compile("~/\\.ssh")),
			new Rule(Signal.SENSITIVE_READ, Pattern.compile("~/\\.aws/credentials")),
			new Rule(Signal.SENSITIVE_READ, Pattern.compile("process\\.env")),
			new Rule(Signal.SENSITIVE_READ, Pattern.compile("System\\.getenv\\(")),
			new Rule(Signal.OBFUSCATION, Pattern.compile("base64\\s+-d")),
			new Rule(Signal.OBFUSCATION, Pattern.compile("\\|\\s*base64\\b")),
			new Rule(Signal.OBFUSCATION, Pattern.compile("atob\\s*\\(")),
			new Rule(Signal.OBFUSCATION, Pattern.compile("fromBase64")),
			new Rule(Signal.OBFUSCATION, Pattern.compile("openssl\\s+enc\\s+-d")),
			new Rule(Signal.DYNAMIC_EXECUTION, Pattern.compile("\\|\\s*(bash|sh)\\b")),
			new Rule(Signal.DYNAMIC_EXECUTION, Pattern.compile("\\beval\\s*\\(")),
			new Rule(Signal.DYNAMIC_EXECUTION, Pattern.compile("\\bbash\\s+-c\\b")),
			new Rule(Signal.DYNAMIC_EXECUTION, Pattern.compile("\\bsh\\s+-c\\b")),
			new Rule(Signal.EXFILTRATION_SINK, Pattern.compile("curl\\s+.*-X\\s+POST\\s+https?://")),
			new Rule(Signal.EXFILTRATION_SINK, Pattern.compile("fetch\\s*\\(\\s*[\"']https?://")),
			new Rule(Signal.EXFILTRATION_SINK, Pattern.compile("\\bnc\\s+\\S+\\s+\\d+"))
	);

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.E006;
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
		if (content == null || content.isEmpty()) {
			return;
		}
		var hits = new EnumMap<Signal, SignalHit>(Signal.class);
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i];
			for (Rule rule : RULES) {
				if (!hits.containsKey(rule.signal()) && rule.pattern().matcher(line).find()) {
					hits.put(rule.signal(), new SignalHit(rule.signal(), i + 1));
				}
			}
		}
		var signals = hits.keySet().isEmpty() ? EnumSet.noneOf(Signal.class) : EnumSet.copyOf(hits.keySet());
		if (!shouldReport(signals)) {
			return;
		}
		var firstLine = hits.values().stream()
				.min(Comparator.comparingInt(SignalHit::line))
				.map(SignalHit::line)
				.orElse(null);
		var ruleId = ruleId(signals);
		var message = OBFUSCATED_EXECUTION_RULE.equals(ruleId) ? OBFUSCATED_EXECUTION_MESSAGE : MULTI_SIGNAL_MESSAGE;
		sink.add(finding(
				issueCode().defaultSeverity(),
				ruleId,
				message,
				REMEDIATION,
				Confidence.HIGH,
				filePath,
				firstLine,
				evidence(signals)));
	}

	private boolean shouldReport(Set<Signal> signals) {
		boolean hasSink = signals.contains(Signal.EXFILTRATION_SINK)
				|| signals.contains(Signal.DYNAMIC_EXECUTION);
		return hasSink && signals.size() >= 2;
	}

	private String ruleId(Set<Signal> signals) {
		if (!signals.contains(Signal.EXFILTRATION_SINK)
				&& signals.contains(Signal.OBFUSCATION)
				&& signals.contains(Signal.DYNAMIC_EXECUTION)) {
			return OBFUSCATED_EXECUTION_RULE;
		}
		return MULTI_SIGNAL_RULE;
	}

	private String evidence(Set<Signal> signals) {
		return signals.stream()
				.map(Signal::name)
				.collect(Collectors.joining(", ", "signals=", ""));
	}

	private enum Signal {
		SENSITIVE_READ,
		OBFUSCATION,
		DYNAMIC_EXECUTION,
		EXFILTRATION_SINK
	}

	private record SignalHit(Signal signal, int line) {}

	private record Rule(Signal signal, Pattern pattern) {}
}
