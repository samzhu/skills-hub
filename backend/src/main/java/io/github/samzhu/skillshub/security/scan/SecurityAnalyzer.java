package io.github.samzhu.skillshub.security.scan;

/**
 * 掃描引擎 SPI — Skills Hub 多引擎安全掃描 Pipeline 的擴充點。
 *
 * <p>所有引擎實作為 Spring bean，在 {@code ScanOrchestrator} 啟動時透過
 * {@code List<SecurityAnalyzer>} 自動注入。實作類使用
 * {@code @ConditionalOnProperty(name = "skillshub.scanner.engines.{name}.enabled")}
 * 控制 bean 是否建立 — 引擎被關閉時 bean 不存在於 list 中，自然不會被執行，
 * 並且其結果不會出現在 SARIF 的 {@code runs[]} 裡（per spec §2.3 決策 #1）。
 *
 * <p>實作合約：
 * <ul>
 *   <li>{@link #name()} 必須與 SARIF {@code tool.driver.name} 一致；
 *       建議與 Spring bean name 一致以便除錯</li>
 *   <li>{@link #phase()} 決定執行階段；同 phase 內順序由 Spring bean 順序決定</li>
 *   <li>{@link #analyze(ScanContext)} 不應拋例外；錯誤情境回傳 {@link AnalysisOutput#empty()}
 *       並可在內部記錄 log。ScanOrchestrator 會用 {@code try/catch} 統一處理拋出的例外
 *       但實作端的 graceful degradation 較利於除錯</li>
 * </ul>
 *
 * @see Phase
 * @see ScanContext
 * @see AnalysisOutput
 */
public interface SecurityAnalyzer {

	/**
	 * 引擎唯一名稱，用於 SARIF {@code tool.driver.name} 與日誌標籤。
	 * @return 引擎名稱，例如 {@code "pattern"}、{@code "secret"}、{@code "metadata"}、
	 *         {@code "llm-judge"}、{@code "meta"}
	 */
	String name();

	/**
	 * 引擎所屬執行階段，決定 ScanOrchestrator 的並行/序列排程。
	 * @return 執行階段
	 */
	Phase phase();

	/**
	 * 分析給定的 scan context，回傳此引擎獨立的 findings 與 notices。
	 *
	 * @param context 掃描輸入（含 skill 內容；Phase 2/3 額外含 phase1Findings）
	 * @return 此引擎的分析輸出；無發現時回傳 {@link AnalysisOutput#empty()}
	 */
	AnalysisOutput analyze(ScanContext context);
}
