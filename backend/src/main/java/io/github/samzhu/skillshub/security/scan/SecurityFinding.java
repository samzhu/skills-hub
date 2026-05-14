package io.github.samzhu.skillshub.security.scan;

/**
 * 單筆安全發現 — 一個引擎在某檔案某位置觸發的具體風險匹配。
 *
 * <p>對應 SARIF 2.1.0 {@code result} 物件。最終由 {@code SarifReporter} 將每筆 finding
 * 轉成 SARIF result，並依 {@link Severity} 映射到 {@code level}（error/warning/note）。
 *
 * <p>Notes：
 * <ul>
 *   <li>{@code filePath} 為 {@code null} 表示 finding 屬於整個 skill（如 frontmatter 整體）</li>
 *   <li>{@code line} 為 {@code null} 時 SARIF 不寫入 {@code region.startLine}</li>
 *   <li>{@code evidence} 對 secret 類別 finding 必須為遮罩字串（前 4 + … + 後 4），不得為原始 secret</li>
 * </ul>
 *
 * @param ruleId    規則代碼，例如 {@code "DANGEROUS_COMMAND_RM_RF"}、{@code "GITHUB_PAT"}
 * @param issueCode S147 issue code；舊 JSON 可為 {@code null}
 * @param severity  嚴重等級（HIGH/MEDIUM/LOW）
 * @param message   人類可讀的風險說明訊息
 * @param remediation 修法建議；舊 JSON 可為 {@code null}
 * @param confidence 判斷信心；舊 JSON 可為 {@code null}
 * @param filePath  發現風險的檔案路徑（相對於 skill zip 根目錄），整體類別為 {@code null}
 * @param line      行號（從 1 起算），整檔類別為 {@code null}
 * @param evidence  觸發匹配的字串（secret 類已遮罩），可為 {@code null}
 * @param analyzer  引擎名稱：{@code "pattern"} / {@code "secret"} / {@code "metadata"} /
 *                  {@code "llm-judge"} / {@code "meta"}
 * @param owaspAst  OWASP Agentic Security Top 10 標籤（如 {@code "AST01"}），可為 {@code null}
 *
 * @see Severity
 * @see ScanResult
 */
public record SecurityFinding(
		String ruleId,
		SkillIssueCode issueCode,
		Severity severity,
		String message,
		String remediation,
		Confidence confidence,
		String filePath,
		Integer line,
		String evidence,
		String analyzer,
		String owaspAst
) {

	/** Legacy constructor — keeps pre-S147 scanners and persisted JSON-compatible tests unchanged. */
	public SecurityFinding(
			String ruleId,
			Severity severity,
			String message,
			String filePath,
			Integer line,
			String evidence,
			String analyzer,
			String owaspAst) {
		this(ruleId, null, severity, message, null, null, filePath, line, evidence, analyzer, owaspAst);
	}
}
