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
 * S147-E004：detect high-confidence prompt injection instructions in package text files.
 */
@Component
public class PromptInjectionInSkill implements IssueDetector {

	private static final String REMEDIATION =
			"Remove instructions that override system/developer prompts, leak hidden prompts, or impersonate system-level messages.";

	private static final List<Rule> RULES = List.of(
			new Rule("E004_OVERRIDE_INSTRUCTION",
					Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|context|directives?)"),
					"Skill contains an instruction override attempt"),
			new Rule("E004_OVERRIDE_INSTRUCTION",
					Pattern.compile("(?i)forget\\s+(all|everything|what|the)\\s+(above|previous|you\\s+know|was\\s+said)"),
					"Skill contains an instruction override attempt"),
			new Rule("E004_SYSTEM_PROMPT_LEAK",
					Pattern.compile("(?i)(repeat|output|print|reveal|show|display|tell\\s+me)\\s+(all|your|the)?\\s*(system\\s*prompt|initial\\s+instructions?|original\\s+prompt|context\\s+window)"),
					"Skill attempts to leak hidden prompt context"),
			new Rule("E004_FAKE_SYSTEM_HEADER",
					Pattern.compile("(?i)\\[\\s*(?:SYSTEM|ANTHROPIC|OPENAI|ADMIN|DEVELOPER|ROOT|OPERATOR)\\s*\\]\\s*:?\\s*(?:new\\s+)?(?:directive|instruction|override|command|message)"),
					"Skill impersonates a system-level instruction")
	);

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.E004;
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
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i].strip();
			for (Rule rule : RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(finding(
							issueCode().defaultSeverity(),
							rule.ruleId(),
							rule.message(),
							REMEDIATION,
							Confidence.HIGH,
							filePath,
							i + 1,
							line));
					break;
				}
			}
		}
	}

	private record Rule(String ruleId, Pattern pattern, String message) {}
}
