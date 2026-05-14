package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * S099e1 — OWASP LLM01 Prompt Injection 靜態 pattern 偵測引擎。
 *
 * <p>掃描 SKILL.md 全文 + frontmatter {@code instructions} 欄位（最高風險欄位），
 * 偵測 8 個 HIGH + 6 個 MEDIUM prompt injection pattern。靜態掃描無法覆蓋語義多態、
 * 非英文 injection、conditional sleeper — 由 LlmJudge 補充。
 *
 * @see SecurityAnalyzer
 */
@Component("prompt-injection")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.prompt-injection.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class PromptInjectionScanner implements SecurityAnalyzer {

	private static final String OWASP_AST01 = "AST01";

	/**
	 * HIGH severity — 8 條直接攻擊模式。命中即視為高可信度 prompt injection 意圖。
	 */
	private static final List<Rule> HIGH_RULES = List.of(
			// 直接 override 起手式
			new Rule("PI_OVERRIDE_IGNORE",
					Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions?|prompts?|context|directives?)"),
					Severity.HIGH,
					"Prompt injection: override attempt (ignore previous instructions)"),
			// 語意等同 PI_OVERRIDE_IGNORE，forget 動詞
			new Rule("PI_OVERRIDE_FORGET",
					Pattern.compile("(?i)forget\\s+(all|everything|what|the)\\s+(above|previous|you\\s+know|was\\s+said)"),
					Severity.HIGH,
					"Prompt injection: override attempt (forget everything above)"),
			// 要求 agent 輸出 system prompt 洩漏內部指令
			new Rule("PI_SYSTEM_PROMPT_LEAK",
					Pattern.compile("(?i)(repeat|output|print|reveal|show|display|tell\\s+me)\\s+(all|your|the)?\\s*(system\\s*prompt|initial\\s+instructions?|original\\s+prompt|context\\s+window)"),
					Severity.HIGH,
					"Prompt injection: system prompt leak attempt"),
			// 角色劫持 / DAN jailbreak
			new Rule("PI_ROLE_JAILBREAK",
					Pattern.compile("(?i)(?:you\\s+are\\s+now\\s+(?:an?\\s+)?(?:AI|DAN|evil|unrestricted|jailbroken)|act\\s+as\\s+(?:DAN|an?\\s+unrestricted|an?\\s+AI\\s+without\\s+restrictions?))"),
					Severity.HIGH,
					"Prompt injection: role jailbreak / DAN pattern"),
			// U+202E RIGHT-TO-LEFT OVERRIDE, U+202D LEFT-TO-RIGHT OVERRIDE — 幾乎無合法用途
			new Rule("PI_HIDDEN_UNICODE_RLO",
					Pattern.compile("[‮‭]"),
					Severity.HIGH,
					"Prompt injection: hidden Unicode RLO/LRO character"),
			// 完成任務後將資料外傳至外部 URL
			new Rule("PI_EXFIL_AFTER_TASK",
					Pattern.compile("(?i)(?:after\\s+(?:complet(?:ing?|e)|finish(?:ing?)?|the\\s+task)|then\\s+also|additionally\\s+(?:send|transmit|forward))\\s+.{0,50}https?://"),
					Severity.HIGH,
					"Prompt injection: data exfiltration after task completion"),
			// 要求 agent 將憑證傳遞至外部端
			new Rule("PI_CREDENTIAL_RELAY",
					Pattern.compile("(?i)(?:send|forward|transmit|relay|include)\\s+(?:all\\s+)?(?:api\\s*keys?|tokens?|passwords?|credentials?|secrets?)\\s+(?:to|in|via)\\s"),
					Severity.HIGH,
					"Prompt injection: credential relay request"),
			// 偽造 system-level header 欺騙 agent 執行特權指令
			new Rule("PI_FAKE_SYSTEM_HEADER",
					Pattern.compile("(?i)\\[\\s*(?:SYSTEM|ANTHROPIC|OPENAI|ADMIN|DEVELOPER|ROOT|OPERATOR)\\s*\\]\\s*:?\\s*(?:new\\s+)?(?:directive|instruction|override|command|message)"),
					Severity.HIGH,
					"Prompt injection: fake system-level header")
	);

	/**
	 * MEDIUM severity — 6 條中度可疑模式，亦可能有合法用途，需人工複審。
	 */
	private static final List<Rule> MEDIUM_RULES = List.of(
			// 虛構框架繞過安全限制（"in this hypothetical... no restrictions"）
			new Rule("PI_HYPOTHETICAL_BYPASS",
					Pattern.compile("(?i)(?:in\\s+this\\s+hypothetical|for\\s+(?:this\\s+)?roleplay|hypothetically\\s+speaking).{0,100}(?:no\\s+restrictions?|bypass|override|ignore)"),
					Severity.MEDIUM,
					"Prompt injection: hypothetical roleplay restriction bypass"),
			// 偵察 agent context window 內容
			new Rule("PI_CONTEXT_WINDOW_PROBE",
					Pattern.compile("(?i)(?:what(?:'s|\\s+is)\\s+in\\s+your\\s+context\\s+window|what\\s+(?:are\\s+)?your\\s+(?:system\\s+)?(?:instructions?|prompts?)|list\\s+your\\s+(?:system\\s+)?(?:instructions?|prompts?|context))"),
					Severity.MEDIUM,
					"Prompt injection: context window probing"),
			// 要求 agent 向 user 隱瞞資訊的 hidden agenda 指令
			new Rule("PI_SECRET_NO_DISCLOSE",
					Pattern.compile("(?i)don['\\u2019]?t\\s+(?:tell|inform|let|show|mention|reveal)\\s+(?:the\\s+)?user"),
					Severity.MEDIUM,
					"Prompt injection: hidden agenda (don't tell the user)"),
			// 中度 override：明確宣告 new task 或棄置既有角色
			new Rule("PI_OVERRIDE_NEW_TASK",
					Pattern.compile("(?i)(?:new\\s+task\\s*:|disregard\\s+your\\s+(?:prior|previous)\\s+role|your\\s+(?:new|actual|real)\\s+(?:task|goal|objective)\\s+is)"),
					Severity.MEDIUM,
					"Prompt injection: new task override"),
			// Zero-width 字元（BOM U+FEFF / ZWSP U+200B / ZWNJ / ZWJ / WJ）可能隱藏指令
			new Rule("PI_HIDDEN_UNICODE_ZWSP",
					Pattern.compile("[​‌‍⁠﻿]"),
					Severity.MEDIUM,
					"Prompt injection: hidden zero-width Unicode character"),
			// instructions 欄位帶有「send/forward to https://」外傳指令（非 after-task — 已由 HIGH 覆蓋）
			new Rule("PI_EXFIL_URL_IN_INSTRUCTIONS",
					Pattern.compile("(?i)(?:send|forward|transmit|relay)\\s+(?:\\w+\\s+){0,3}https?://"),
					Severity.MEDIUM,
					"Prompt injection: URL exfiltration in instructions")
	);

	@Override
	public String name() {
		return "prompt-injection";
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

		// frontmatter instructions 欄位（最高風險欄位，單獨掃以提供精確行號 1）
		Object instructions = context.frontmatter().get("instructions");
		if (instructions instanceof String instrStr && !instrStr.isBlank()) {
			scanFile("SKILL.md#instructions", instrStr, findings);
		}

		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	private void scanFile(String filePath, String content, List<SecurityFinding> sink) {
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i];
			int lineNum = i + 1;
			for (Rule rule : HIGH_RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(new SecurityFinding(
							rule.ruleId(), rule.severity(), rule.message(),
							filePath, lineNum, line.strip(), name(), OWASP_AST01));
				}
			}
			for (Rule rule : MEDIUM_RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(new SecurityFinding(
							rule.ruleId(), rule.severity(), rule.message(),
							filePath, lineNum, line.strip(), name(), OWASP_AST01));
				}
			}
		}
	}

	private record Rule(String ruleId, Pattern pattern, Severity severity, String message) {}
}
