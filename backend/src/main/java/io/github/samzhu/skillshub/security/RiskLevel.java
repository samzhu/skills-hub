package io.github.samzhu.skillshub.security;

/**
 * 風險等級列舉，用於描述 Skill 套件掃描後的整體風險程度。
 *
 * <p>S096c 4-tier scheme（對齊 Cisco Skill Scanner + CVSS None band；per ADR-future + PRD D27）：
 *
 * <ul>
 *   <li>{@link #NONE} — 0 findings + 無 scripts/ + 無 allowed-tools；純文件 skill，掃描器找不到 known patterns。
 *       注意：NONE ≠ certified safe，僅表示 scanner 未抓到威脅指紋。</li>
 *   <li>{@link #LOW} — 0 findings 但聲明 capability（含 scripts 或 allowed-tools），無 demonstrated 風險；
 *       OR findings 全為 LOW severity</li>
 *   <li>{@link #MEDIUM} — 含腳本但無危險模式，需人工複核（max severity = MEDIUM）</li>
 *   <li>{@link #HIGH} — 偵測到危險指令或敏感路徑存取，應優先審查（max severity = HIGH）</li>
 * </ul>
 */
public enum RiskLevel {
	/** S096c 新增：純文件 skill，0 findings + 無 capability declaration。 */
	NONE,
	/** 0 findings 但聲明 capability（含 scripts 或 allowed-tools）；OR findings 全為 LOW。 */
	LOW,
	/** 含腳本但無危險模式，需人工複核。 */
	MEDIUM,
	/** 含危險指令或敏感路徑，應優先審查。 */
	HIGH
}
