package io.github.samzhu.skillshub.security.scan;

/**
 * 安全發現嚴重等級 — 用於 {@link SecurityFinding} 的分級與最終 {@code riskLevel} 的彙整。
 *
 * <p>Phase 3 (MetaAnalyzer) 與 ScanOrchestrator 的最終彙整採取 max severity 規則：
 * 任一引擎回報 HIGH 即整體 HIGH；無 HIGH 但有 MEDIUM 即整體 MEDIUM；皆為 LOW 則 LOW。
 *
 * <p>對應 SARIF 2.1.0 {@code result.level} 映射：HIGH → {@code "error"}、
 * MEDIUM → {@code "warning"}、LOW → {@code "note"}。
 *
 * @see SecurityFinding
 * @see ScanResult
 */
public enum Severity {
	/** 高風險 — 命中明確危險指令、外洩 secret、或 LLM 判定為惡意 */
	HIGH,
	/** 中風險 — 含外部依賴、可疑但無確證的 pattern */
	MEDIUM,
	/** 低風險 — 無腳本、無危險 pattern；通常以 notice 形式呈現 */
	LOW
}
