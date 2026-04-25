package io.github.samzhu.skillshub.security;

/**
 * 風險等級列舉，用於描述 Skill 套件掃描後的整體風險程度。
 *
 * <ul>
 *   <li>{@link #LOW} — 無腳本檔案，視為低風險</li>
 *   <li>{@link #MEDIUM} — 含腳本但未發現危險模式，視為中風險</li>
 *   <li>{@link #HIGH} — 偵測到危險指令或敏感路徑存取，視為高風險</li>
 * </ul>
 */
public enum RiskLevel {
	/** 無腳本，風險最低。 */
	LOW,
	/** 含腳本但無危險模式，需人工複核。 */
	MEDIUM,
	/** 含危險指令或敏感路徑，應優先審查。 */
	HIGH
}
