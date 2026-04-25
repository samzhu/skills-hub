package io.github.samzhu.skillshub.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Skill 套件靜態掃描器，依正規表示式規則分析腳本內容並產生 {@link ScanResult}。
 *
 * <p>風險等級判定策略：</p>
 * <ol>
 *   <li><b>LOW</b>：套件完全不含腳本檔案 — 無可執行程式碼，視為最安全</li>
 *   <li><b>MEDIUM</b>：含腳本但未命中任何危險規則 — 存在外部 URL 等低風險項目或純淨腳本，
 *       需要人工複核但不立即封鎖</li>
 *   <li><b>HIGH</b>：命中 {@code DANGEROUS_COMMAND}、{@code PIPE_TO_SHELL} 或
 *       {@code SENSITIVE_PATH} 任一規則 — 具有明確惡意意圖或資安疑慮，應優先審查</li>
 * </ol>
 */
@Component
public class RiskScanner {

	/**
	 * 危險指令規則集：比對 rm -rf、chmod 777、pipe-to-shell 等常見攻擊手法。
	 * 命中任一規則即將整體等級升至 {@link RiskLevel#HIGH}。
	 */
	static final List<PatternRule> DANGEROUS_COMMANDS = List.of(
			new PatternRule(Pattern.compile("rm\\s+-rf"), "DANGEROUS_COMMAND", "Dangerous command: rm -rf"),
			new PatternRule(Pattern.compile("chmod\\s+777"), "DANGEROUS_COMMAND", "Dangerous command: chmod 777"),
			new PatternRule(Pattern.compile("curl.*\\|.*(?:bash|sh)"), "PIPE_TO_SHELL", "Pipe to shell: curl | bash/sh"),
			new PatternRule(Pattern.compile("wget.*\\|.*(?:bash|sh)"), "PIPE_TO_SHELL", "Pipe to shell: wget | bash/sh")
	);

	/**
	 * 敏感路徑規則集：比對 SSH 金鑰、AWS 憑證、系統帳密等敏感位置。
	 * 命中任一規則即將整體等級升至 {@link RiskLevel#HIGH}。
	 */
	static final List<PatternRule> SENSITIVE_PATHS = List.of(
			new PatternRule(Pattern.compile("~/\\.ssh"), "SENSITIVE_PATH", "Access to ~/.ssh"),
			new PatternRule(Pattern.compile("~/\\.aws"), "SENSITIVE_PATH", "Access to ~/.aws"),
			new PatternRule(Pattern.compile("/etc/passwd"), "SENSITIVE_PATH", "Access to /etc/passwd"),
			new PatternRule(Pattern.compile("/etc/shadow"), "SENSITIVE_PATH", "Access to /etc/shadow")
	);

	/**
	 * 外部 URL 正規表示式：用於標記腳本中的任何 HTTP/HTTPS 呼叫，
	 * 屬低風險提示（歸類為 {@code EXTERNAL_URL}），不直接升為 HIGH。
	 */
	static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"']+");

	/**
	 * 掃描指定的腳本檔案集合，回傳整體風險評估結果。
	 *
	 * @param scriptFiles 以檔案名稱為 key、檔案內容為 value 的 Map；空 Map 代表套件無腳本
	 * @return 包含風險等級與所有發現項目的 {@link ScanResult}
	 */
	public ScanResult scan(Map<String, String> scriptFiles) {
		// 套件不含任何腳本 → 無可執行程式碼，直接判定為低風險
		if (scriptFiles.isEmpty()) {
			return new ScanResult(RiskLevel.LOW, List.of());
		}

		var findings = new ArrayList<RiskFinding>();

		for (var entry : scriptFiles.entrySet()) {
			var file = entry.getKey();
			var content = entry.getValue();
			// 依換行符切割，逐行掃描以取得精確行號
			var lines = content.split("\n");

			for (int i = 0; i < lines.length; i++) {
				var line = lines[i];
				int lineNum = i + 1;

				// 檢查危險指令（rm -rf、chmod 777、pipe-to-shell 等）
				for (var rule : DANGEROUS_COMMANDS) {
					if (rule.pattern.matcher(line).find()) {
						findings.add(new RiskFinding(rule.type, rule.message, file, lineNum, rule.pattern.pattern()));
					}
				}

				// 檢查敏感系統路徑（SSH、AWS、/etc/passwd 等）
				for (var rule : SENSITIVE_PATHS) {
					if (rule.pattern.matcher(line).find()) {
						findings.add(new RiskFinding(rule.type, rule.message, file, lineNum, rule.pattern.pattern()));
					}
				}

				// 記錄所有外部 URL（不升高等級，僅作為供稽核的透明度資訊）
				var urlMatcher = URL_PATTERN.matcher(line);
				while (urlMatcher.find()) {
					findings.add(new RiskFinding("EXTERNAL_URL", "External URL: " + urlMatcher.group(), file, lineNum, urlMatcher.group()));
				}
			}
		}

		// 含腳本且命中危險規則 → HIGH；含腳本但無危險規則（可能只有外部 URL）→ MEDIUM
		var hasHighRisk = findings.stream().anyMatch(f ->
				"DANGEROUS_COMMAND".equals(f.type()) || "PIPE_TO_SHELL".equals(f.type()) || "SENSITIVE_PATH".equals(f.type()));

		var level = hasHighRisk ? RiskLevel.HIGH : RiskLevel.MEDIUM;
		return new ScanResult(level, List.copyOf(findings));
	}

	/**
	 * 掃描規則定義，包含正規表示式、風險類型代碼及說明訊息。
	 *
	 * @param pattern 用於比對腳本內容的正規表示式
	 * @param type    風險類型代碼，對應 {@link RiskFinding#type()}
	 * @param message 人類可讀的風險說明
	 */
	record PatternRule(Pattern pattern, String type, String message) {}

}
