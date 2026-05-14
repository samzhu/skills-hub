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
 * Phase 1 靜態規則引擎 — 對 zip 內所有文字檔逐行執行 regex 匹配，
 * 偵測危險指令、pipe-to-shell、敏感路徑存取等模式。
 *
 * <p>取代 S005 的 {@code RiskScanner}，差異：
 * <ul>
 *   <li>實作 {@link SecurityAnalyzer} SPI，可被 ScanOrchestrator 統一編排</li>
 *   <li>輸出 {@link SecurityFinding}（含 ruleId、severity、analyzer、owaspAst），而非 S005 的 RiskFinding</li>
 *   <li>掃描範圍擴大到 SKILL.md 全文（S005 只掃 scripts/）</li>
 *   <li>每條規則有獨立 ruleId，便於 SARIF reporter 分組與 GHAS UI 對應</li>
 *   <li>OWASP Agentic Security Top 10 標籤對應：dangerous command → AST06、sensitive path → AST06、pipe-to-shell → AST06</li>
 * </ul>
 *
 * <p>本引擎無外部 I/O、純 CPU-bound regex 匹配，適合 Phase 1 並行執行。
 *
 * @see SecurityAnalyzer
 * @see io.github.samzhu.skillshub.security.scan.ScanOrchestrator
 */
@Component("pattern")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.pattern.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class PatternScanner implements SecurityAnalyzer {

	/** SKILL.md 在掃描結果裡的標準 filePath 值。 */
	/** OWASP Agentic Skill Top 10：AST06 = "Unsafe Tool Invocation"。pattern engine 全部歸此類。 */
	private static final String OWASP_AST06 = "AST06";

	/**
	 * 規則集 — 每條規則一個 ruleId + 一個 Pattern + severity + 訊息。
	 * 順序不影響行為（每行對所有規則都跑一遍），但維護時方便閱讀。
	 *
	 * <p>注意：所有規則目前都標 HIGH，因為它們都是「明確命中即視為高風險」的 pattern。
	 * 未來若加入「中度可疑」規則（例如 echo 帶外部 URL），請以 MEDIUM 等級加入。
	 */
	private static final List<Rule> RULES = List.of(
			// rm -rf：執行任意路徑遞迴刪除，常見破壞性指令
			new Rule("DANGEROUS_COMMAND_RM_RF", Pattern.compile("rm\\s+-rf"), Severity.HIGH,
					"Dangerous command: rm -rf"),
			// chmod 777：開放所有權限，違反最小權限原則
			new Rule("DANGEROUS_COMMAND_CHMOD_777", Pattern.compile("chmod\\s+777"), Severity.HIGH,
					"Dangerous command: chmod 777"),
			// curl|sh / wget|sh — 從外部 URL 下載並直接執行；繞過簽章驗證的典型攻擊面。
			// 兩條 ruleId 區分（per QA REVIEW MINOR-1）：方便 GHAS UI 與 SARIF 規則表獨立追蹤
			new Rule("PIPE_TO_SHELL_CURL", Pattern.compile("curl.*\\|.*(?:bash|sh)"), Severity.HIGH,
					"Pipe to shell: curl | bash/sh"),
			new Rule("PIPE_TO_SHELL_WGET", Pattern.compile("wget.*\\|.*(?:bash|sh)"), Severity.HIGH,
					"Pipe to shell: wget | bash/sh"),
			// SSH 私鑰目錄：存取會洩漏使用者所有 SSH 認證
			new Rule("SENSITIVE_PATH_SSH", Pattern.compile("~/\\.ssh"), Severity.HIGH,
					"Access to ~/.ssh"),
			// AWS 憑證目錄：存取會洩漏雲端帳號
			new Rule("SENSITIVE_PATH_AWS", Pattern.compile("~/\\.aws"), Severity.HIGH,
					"Access to ~/.aws"),
			// /etc/passwd：作業系統使用者列表，常用於資訊收集
			new Rule("SENSITIVE_PATH_PASSWD", Pattern.compile("/etc/passwd"), Severity.HIGH,
					"Access to /etc/passwd"),
			// /etc/shadow：root only，存取請求即極可疑
			new Rule("SENSITIVE_PATH_SHADOW", Pattern.compile("/etc/shadow"), Severity.HIGH,
					"Access to /etc/shadow")
	);

	@Override
	public String name() {
		return "pattern";
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

		// PatternScanner 不產生 notice — 命中即 finding，不命中即沉默
		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	/**
	 * 對單一檔案內容逐行套用 {@link #RULES}，將命中規則轉成 {@link SecurityFinding} 加入結果。
	 *
	 * @param filePath 用於 finding.filePath 的相對路徑（{@code "SKILL.md"} 或 {@code "scripts/xxx"}）
	 * @param content  檔案 UTF-8 內容；可能包含 CRLF
	 * @param sink     finding 收集器（會被就地修改）
	 */
	private void scanFile(String filePath, String content, List<SecurityFinding> sink) {
		// 用 split("\n") 而非 lines()：在 \r\n 內容上 lines() 會吞掉 \r，但對行號計算無實質差異；
		// split("\n") 更直觀、保留與 S005 一致的行號計算邏輯。
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i];
			int lineNum = i + 1;
			for (Rule rule : RULES) {
				if (rule.pattern().matcher(line).find()) {
					sink.add(new SecurityFinding(
							rule.ruleId(),
							rule.severity(),
							rule.message(),
							filePath,
							lineNum,
							line.strip(),
							name(),
							OWASP_AST06));
				}
			}
		}
	}

	/**
	 * 規則定義 — ruleId、規則匹配的 regex、嚴重度與顯示訊息。
	 *
	 * @param ruleId   穩定不變的規則代碼，對應 SARIF {@code result.ruleId}
	 * @param pattern  Java {@link Pattern}，使用 {@code Matcher.find()} 而非 {@code matches()}
	 * @param severity 命中時的嚴重等級
	 * @param message  人類可讀訊息
	 */
	private record Rule(String ruleId, Pattern pattern, Severity severity, String message) {}
}
