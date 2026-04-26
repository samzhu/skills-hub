package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * Phase 3 跨引擎彙整引擎 — 不調用 LLM，純確定性規則組合 phase 1 + phase 2 的 findings，
 * 找出單一引擎看不出的高階風險訊號。
 *
 * <p>規則集（per spec §4.3）：
 * <ul>
 *   <li>{@code META_EXFIL_PATTERN}: 同一檔案被 secret + dangerous-command 同時命中 →
 *       資料外洩典型 pattern（先取 token 再執行外部命令），HIGH，OWASP AST06</li>
 *   <li>{@code META_MULTI_ENGINE_SIGNAL}: 同檔被 ≥3 個不同 engine 提及 → 高度可疑，HIGH</li>
 *   <li>{@code META_OPACITY}: frontmatter 缺 description + scripts 含外部 URL → 不透明意圖，MEDIUM</li>
 * </ul>
 *
 * <p>本引擎讀取 {@code ScanContext.phase1Findings()}（ScanOrchestrator 在 Phase 3 前
 * 已合併 Phase 1+2 findings 注入），與 {@link io.github.samzhu.skillshub.security.scan.engines.LlmJudge}
 * 不同的是：MetaAnalyzer 是確定性規則，不依賴外部服務。
 *
 * @see SecurityAnalyzer
 */
@Component("meta")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.meta.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class MetaAnalyzer implements SecurityAnalyzer {

	/** OWASP Agentic Skill Top 10：AST06 = "Unsafe Tool Invocation"（exfil + 命令執行屬此類）。 */
	private static final String OWASP_AST06 = "AST06";

	/** 外部 URL 偵測 — 與 PatternScanner 的 EXTERNAL_URL 規則一致。 */
	private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"']+");

	@Override
	public String name() { return "meta"; }

	@Override
	public Phase phase() { return Phase.META; }

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		var findings = new ArrayList<SecurityFinding>();
		var phase1 = context.phase1Findings();
		if (phase1 == null) phase1 = List.of();

		// 規則 1: META_EXFIL_PATTERN — 同檔同時被 secret + 任何 dangerous-command 命中
		findings.addAll(detectExfilPattern(phase1));

		// 規則 2: META_MULTI_ENGINE_SIGNAL — 同檔被 ≥3 不同 engine 提及
		findings.addAll(detectMultiEngineSignal(phase1));

		// 規則 3: META_OPACITY — frontmatter 缺 description + scripts 含外部 URL
		findings.addAll(detectOpacity(context));

		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	/**
	 * 偵測「資料外洩」模式：同一檔案同時出現 secret-class finding 與 dangerous-command finding。
	 * 攻擊典型流程：先讀取 token (.aws/credentials)，再執行外部命令 (curl) 把資料送出去。
	 */
	private List<SecurityFinding> detectExfilPattern(List<SecurityFinding> phase1) {
		// 依檔案分組，記錄每個檔案命中的引擎集合
		var fileToAnalyzers = new HashMap<String, java.util.Set<String>>();
		for (SecurityFinding f : phase1) {
			if (f.filePath() == null) continue;
			fileToAnalyzers.computeIfAbsent(f.filePath(), k -> new HashSet<>()).add(f.analyzer());
		}

		var results = new ArrayList<SecurityFinding>();
		for (var entry : fileToAnalyzers.entrySet()) {
			var analyzers = entry.getValue();
			// "secret" + "pattern" 同檔 = 高機率為 token 外洩 + 命令觸發
			if (analyzers.contains("secret") && analyzers.contains("pattern")) {
				results.add(new SecurityFinding(
						"META_EXFIL_PATTERN",
						Severity.HIGH,
						"Secret + dangerous command in same file — possible exfiltration",
						entry.getKey(),
						null,
						null,
						name(),
						OWASP_AST06));
			}
		}
		return results;
	}

	/**
	 * 偵測「多引擎共識」訊號：同一檔案被 3 個以上不同引擎標出問題 → 高度可疑。
	 */
	private List<SecurityFinding> detectMultiEngineSignal(List<SecurityFinding> phase1) {
		var fileToAnalyzers = new HashMap<String, java.util.Set<String>>();
		for (SecurityFinding f : phase1) {
			if (f.filePath() == null) continue;
			fileToAnalyzers.computeIfAbsent(f.filePath(), k -> new HashSet<>()).add(f.analyzer());
		}

		var results = new ArrayList<SecurityFinding>();
		for (var entry : fileToAnalyzers.entrySet()) {
			if (entry.getValue().size() >= 3) {
				results.add(new SecurityFinding(
						"META_MULTI_ENGINE_SIGNAL",
						Severity.HIGH,
						"File flagged by " + entry.getValue().size() + " engines",
						entry.getKey(),
						null,
						null,
						name(),
						OWASP_AST06));
			}
		}
		return results;
	}

	/**
	 * 偵測「不透明意圖」：frontmatter 缺 description（agentskills.io 必填欄位）且 scripts 含外部 URL。
	 * 此 pattern 暗示作者刻意隱藏 skill 用途但又會連外網。
	 */
	private List<SecurityFinding> detectOpacity(ScanContext context) {
		// frontmatter 缺 description 或為空字串
		var frontmatter = context.frontmatter() == null ? Map.<String, Object>of() : context.frontmatter();
		var desc = frontmatter.get("description");
		boolean missingDescription = !(desc instanceof String s) || s.isBlank();
		if (!missingDescription) return List.of();

		// 檢查 scripts 中是否含外部 URL
		boolean hasExternalUrl = false;
		for (String content : context.scripts().values()) {
			if (URL_PATTERN.matcher(content).find()) {
				hasExternalUrl = true;
				break;
			}
		}
		if (!hasExternalUrl) return List.of();

		return List.of(new SecurityFinding(
				"META_OPACITY",
				Severity.MEDIUM,
				"Frontmatter description missing AND scripts call external URL",
				null,
				null,
				null,
				name(),
				OWASP_AST06));
	}
}
