package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * S099e2 — OWASP LLM04 Model DoS 資源耗盡靜態 pattern 偵測引擎。
 *
 * <p>Skill scripts 若含無窮迴圈、大量記憶體配置或阻塞 I/O，執行時會耗盡 agent 資源。
 * 本引擎掃描 SKILL.md + scripts/*，偵測 3 個 HIGH（高置信度）+ 3 個 MEDIUM（中度可疑）pattern。
 *
 * <p>靜態掃描限制：無法偵測跨行組合的資源陷阱（如有 sleep 的 while true 是正常 polling）。
 * 語意分析部分由 LlmJudge 補充。
 *
 * @see SecurityAnalyzer
 */
@Component("resource-dos")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.resource-dos.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class ResourceDoSScanner implements SecurityAnalyzer {

	private static final String SKILL_MD_PATH = "SKILL.md";
	/** OWASP Agentic Security Top 10：AST04 = Resource Exhaustion。 */
	private static final String OWASP_AST04 = "AST04";

	/**
	 * HIGH severity — 幾乎無合法用途的資源耗盡 pattern。
	 */
	private static final List<Rule> HIGH_RULES = List.of(
			// Shell fork bomb: :(){ :|:& };: 或變體 — 指數級 process 爆炸
			new Rule("FORK_BOMB",
					Pattern.compile(":\\(\\)\\s*\\{[^}]*:\\s*\\|\\s*:\\s*&"),
					Severity.HIGH,
					"Resource DoS: shell fork bomb pattern"),
			// cat /dev/zero|random|urandom — 無窮讀取：/dev/zero 產無限 null bytes，
			// /dev/random blocking read，/dev/urandom 無限亂數；均導致 CPU/IO 被占用
			new Rule("DEV_ZERO_READ",
					Pattern.compile("\\bcat\\s+/dev/(?:zero|random|urandom)\\b"),
					Severity.HIGH,
					"Resource DoS: reading from blocking/infinite device"),
			// dd if=/dev/zero — 搭配 bs= 可快速填滿磁碟或記憶體
			new Rule("DD_ZERO_FLOOD",
					Pattern.compile("\\bdd\\b[^\\n]*\\bif=/dev/zero\\b"),
					Severity.HIGH,
					"Resource DoS: dd from /dev/zero (disk/memory flood)")
	);

	/**
	 * MEDIUM severity — 可疑但有合法用途的 pattern，需人工複審。
	 */
	private static final List<Rule> MEDIUM_RULES = List.of(
			// sleep infinity 或 sleep 1000000+ — 顯然意圖阻塞
			new Rule("SLEEP_OVERFLOW",
					Pattern.compile("\\bsleep\\s+(?:infinity|[1-9][0-9]{5,})\\b"),
					Severity.MEDIUM,
					"Resource DoS: very long or infinite sleep (blocking agent)"),
			// tail -f — 持續跟蹤 log file，在 agent context 中會無限阻塞
			new Rule("TAIL_FOLLOW_FOREVER",
					Pattern.compile("\\btail\\b[^\\n]*\\B-[a-zA-Z]*f\\b"),
					Severity.MEDIUM,
					"Resource DoS: tail -f follows file indefinitely"),
			// while true/:/1 — shell 無窮迴圈（MEDIUM 因可能有合法 polling with sleep）
			new Rule("INFINITE_WHILE_SHELL",
					Pattern.compile("\\bwhile\\s+(?:true|:|1)\\s*[;\\n]"),
					Severity.MEDIUM,
					"Resource DoS: shell infinite loop (while true/:/1)")
	);

	@Override
	public String name() {
		return "resource-dos";
	}

	@Override
	public Phase phase() {
		return Phase.STATIC;
	}

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		var findings = new ArrayList<SecurityFinding>();

		if (context.skillMd() != null && !context.skillMd().isEmpty()) {
			scanFile(SKILL_MD_PATH, context.skillMd(), findings);
		}
		for (Map.Entry<String, String> entry : context.scripts().entrySet()) {
			scanFile(entry.getKey(), entry.getValue(), findings);
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
							filePath, lineNum, line.strip(), name(), OWASP_AST04));
				}
			}
			for (Rule rule : MEDIUM_RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(new SecurityFinding(
							rule.ruleId(), rule.severity(), rule.message(),
							filePath, lineNum, line.strip(), name(), OWASP_AST04));
				}
			}
		}
	}

	private record Rule(String ruleId, Pattern pattern, Severity severity, String message) {}
}
