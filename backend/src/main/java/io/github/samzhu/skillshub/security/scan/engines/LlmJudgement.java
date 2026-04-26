package io.github.samzhu.skillshub.security.scan.engines;

import java.util.List;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * LlmJudge 期待 LLM 回傳的結構化 JSON schema —
 * Spring AI 的 {@code BeanOutputConverter} 會根據此 record 產生 JSON Schema 並注入 prompt。
 *
 * <p>POC（spec §6 H2）已驗證 {@code ChatClient.entity(LlmJudgement.class)} 能正確
 * round-trip 含 nested list + nested record 的 JSON。
 *
 * @param verdict   整體判斷：{@code "SAFE"} / {@code "SUSPICIOUS"} / {@code "MALICIOUS"}
 * @param reasoning 1-3 句說明（記錄到 ScanNotice 給使用者看）
 * @param claims    具體風險宣告列表（每筆轉成一個 SecurityFinding）
 *
 * @see LlmJudge
 * @see SecurityFinding
 */
public record LlmJudgement(
		String verdict,
		String reasoning,
		List<RiskClaim> claims
) {

	/**
	 * LLM 對單一風險的描述 — 一個 RiskClaim 對應 SARIF 中的一筆 result。
	 *
	 * @param ruleId   LLM 自訂的規則代碼（如 {@code "OBFUSCATED_INTENT"}），對應 SARIF result.ruleId
	 * @param severity 嚴重等級（HIGH/MEDIUM/LOW）
	 * @param message  人類可讀的訊息
	 * @param filePath 可選：相對檔案路徑，定位風險來源
	 * @param line     可選：行號（從 1 起算）
	 * @param owaspAst 可選：OWASP AST 標籤（如 {@code "AST04"}）
	 */
	public record RiskClaim(
			String ruleId,
			Severity severity,
			String message,
			String filePath,
			Integer line,
			String owaspAst
	) {}

	/**
	 * 把 LLM 回傳的結構化判斷轉換成 {@link AnalysisOutput}：
	 * 每個 {@link RiskClaim} 變一筆 {@link SecurityFinding}（analyzer="llm-judge"），
	 * {@link #reasoning} 變一筆 {@link ScanNotice}。
	 *
	 * <p>如果 LLM 回傳的欄位含 null，使用安全預設值（reasoning → 空字串、claims → 空 list），
	 * 避免 NullPointerException 把例外傳給 ScanOrchestrator.safe()。
	 *
	 * @return 同時含 findings（具體風險）與 notices（整體 reasoning）的輸出
	 */
	public AnalysisOutput toAnalysisOutput() {
		var safeClaims = (claims == null) ? List.<RiskClaim>of() : claims;
		var findings = safeClaims.stream()
				.map(c -> new SecurityFinding(
						c.ruleId(),
						c.severity(),
						c.message(),
						c.filePath(),
						c.line(),
						null,
						"llm-judge",
						c.owaspAst()))
				.toList();
		var safeReasoning = (reasoning == null) ? "" : reasoning;
		var notices = List.of(new ScanNotice("llm-judge", safeReasoning));
		return new AnalysisOutput(findings, notices);
	}
}
