package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecretPatternCatalog;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * Phase 1 靜態引擎 — 在 zip 內所有文字檔偵測 API key、token、私鑰等敏感字串。
 *
 * <p>規則設計原則：
 * <ul>
 *   <li>規則來源啟發自 gitleaks（MIT 授權，<a href="https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml">gitleaks.toml</a>）
 *       與 OWASP Agentic Skill Top 10：AST01「Prompt / Credential 注入」</li>
 *   <li>每條規則一個 ruleId 對應 SARIF {@code result.ruleId}，方便 GHAS UI 分組</li>
 *   <li>命中時 {@link SecurityFinding#evidence()} 必須遮罩，避免日誌或前端顯示時二次外洩</li>
 *   <li>嚴重度全部 HIGH —— secret 一旦進入版本控制就不再「機密」，無 MEDIUM 中間態</li>
 * </ul>
 *
 * <p>本引擎為純 regex CPU-bound 計算，可在 Phase 1 並行執行；不持有狀態。
 *
 * @see SecurityAnalyzer
 * @see #maskEvidence(String)
 */
@Component("secret")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.secret.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class SecretScanner implements SecurityAnalyzer {

	/** OWASP Agentic Skill Top 10：AST01 = "Credential / Prompt Injection"。 */
	private static final String OWASP_AST01 = "AST01";

	@Override
	public String name() { return "secret"; }

	@Override
	public Phase phase() { return Phase.STATIC; }

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		var findings = new ArrayList<SecurityFinding>();

		for (var entry : context.packageFiles().entrySet()) {
			scanFile(entry.getKey(), entry.getValue(), findings);
		}

		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	/**
	 * 對單一檔案內容逐行套用規則集，命中即建立含遮罩 evidence 的 finding。
	 */
	private void scanFile(String filePath, String content, List<SecurityFinding> sink) {
		for (SecretPatternCatalog.Match match : SecretPatternCatalog.scanFile(filePath, content)) {
			sink.add(new SecurityFinding(
					match.ruleId(),
					Severity.HIGH,
					"Detected " + match.ruleId().toLowerCase().replace('_', ' '),
					filePath,
					match.line(),
					match.maskedEvidence(),
					name(),
					OWASP_AST01));
		}
	}

	/**
	 * 對 secret 字串遮罩 — 短於 8 字元時整段以 {@code "***"} 取代（怕反推），
	 * 否則保留前 4 + {@code "…"} + 後 4，方便人類辨識規則命中但無法外洩原始值。
	 *
	 * <p>package-private visibility 讓 SecretScannerTest 可直接驗證遮罩邏輯。
	 *
	 * @param raw 原始 secret 文字
	 * @return 遮罩後字串
	 */
	static String maskEvidence(String raw) {
		return SecretPatternCatalog.maskEvidence(raw);
	}
}
