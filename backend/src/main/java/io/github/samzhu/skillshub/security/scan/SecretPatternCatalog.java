package io.github.samzhu.skillshub.security.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * S147-W008：secret pattern catalog shared by legacy SecretScanner and issue-code detectors.
 */
public final class SecretPatternCatalog {

	private SecretPatternCatalog() {}

	private static final List<Rule> RULES = List.of(
			new Rule("AWS_ACCESS_KEY_ID",
					Pattern.compile("\\b(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|ASIA)[A-Z0-9]{16}\\b")),
			new Rule("AWS_SECRET_KEY",
					Pattern.compile("(?i)aws[^\\n]{0,30}['\"]?[0-9a-zA-Z/+]{40}['\"]?")),
			new Rule("GOOGLE_API_KEY",
					Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
			new Rule("GITHUB_PAT",
					Pattern.compile("\\bghp_[A-Za-z0-9]{36}\\b")),
			new Rule("GITHUB_FINE_GRAINED_PAT",
					Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{82}\\b")),
			new Rule("OPENAI_KEY",
					Pattern.compile("\\bsk-[A-Za-z0-9]{48}\\b")),
			new Rule("JWT",
					Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")),
			new Rule("PEM_PRIVATE_KEY",
					Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
			new Rule("SLACK_WEBHOOK",
					Pattern.compile("https://hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+")),
			new Rule("GENERIC_BEARER",
					Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}")),
			new Rule("ANTHROPIC_API_KEY",
					Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
			new Rule("STRIPE_API_KEY",
					Pattern.compile("\\bsk_(?:live|test)_[A-Za-z0-9]{24,}\\b")),
			new Rule("HF_ACCESS_TOKEN",
					Pattern.compile("\\bhf_[A-Za-z0-9]{30,}\\b")),
			new Rule("NPM_TOKEN",
					Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b")),
			new Rule("DB_CONN_WITH_PASSWORD",
					Pattern.compile("(?i)(?:postgresql|mysql|mongodb|redis)(?:\\+[a-z]+)?://[^:@\\s]+:[^@\\s]{6,}@")),
			new Rule("GENERIC_HARDCODED_PASSWORD",
					Pattern.compile("(?i)(?:password|passwd|api_?key|secret_?key|access_?key)\\s*[=:]\\s*[\"'][^\"'\\s]{8,}[\"']"))
	);

	public static List<Match> scanFile(String filePath, String content) {
		if (content == null || content.isEmpty()) {
			return List.of();
		}
		var matches = new ArrayList<Match>();
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i];
			int lineNum = i + 1;
			for (Rule rule : RULES) {
				var matcher = rule.pattern().matcher(line);
				while (matcher.find()) {
					var raw = matcher.group();
					if (!isPlaceholder(raw)) {
						matches.add(new Match(rule.ruleId(), filePath, lineNum, maskEvidence(raw)));
					}
				}
			}
		}
		return List.copyOf(matches);
	}

	public static String maskEvidence(String raw) {
		if (raw == null || raw.length() < 8) {
			return "***";
		}
		return raw.substring(0, 4) + "…" + raw.substring(raw.length() - 4);
	}

	private static boolean isPlaceholder(String raw) {
		if (raw == null) {
			return false;
		}
		var upper = raw.toUpperCase(java.util.Locale.ROOT);
		return upper.contains("YOUR_")
				|| upper.contains("_HERE")
				|| upper.contains("REPLACE_ME")
				|| upper.contains("PLACEHOLDER");
	}

	public record Match(String ruleId, String filePath, int line, String maskedEvidence) {}

	private record Rule(String ruleId, Pattern pattern) {}
}
