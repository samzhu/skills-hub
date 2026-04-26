package io.github.samzhu.skillshub.security.scan;

/**
 * 掃描引擎執行階段 — 決定 {@code ScanOrchestrator} 如何分組與排程 {@link SecurityAnalyzer}。
 *
 * <ul>
 *   <li>{@link #STATIC}：純靜態規則匹配，無 I/O / 外部呼叫，可並行執行</li>
 *   <li>{@link #LLM}：需呼叫外部 LLM API，必須等待 STATIC 完成後序列執行</li>
 *   <li>{@link #META}：跨引擎彙整與規則組合，序列執行於 LLM 之後</li>
 * </ul>
 *
 * <p>Phase 1 (STATIC) 由 ScanOrchestrator 透過 virtual-thread executor 並行執行；
 * Phase 2 (LLM) 與 Phase 3 (META) 序列執行，因為它們需要看到先前 phase 的 findings。
 *
 * @see SecurityAnalyzer#phase()
 */
public enum Phase {
	/** 靜態分析引擎（PatternScanner、SecretScanner、MetadataValidator） */
	STATIC,
	/** LLM 語意判斷引擎（LlmJudge） */
	LLM,
	/** 跨引擎彙整引擎（MetaAnalyzer） */
	META
}
