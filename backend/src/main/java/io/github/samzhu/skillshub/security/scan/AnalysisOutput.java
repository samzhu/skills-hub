package io.github.samzhu.skillshub.security.scan;

import java.util.List;

/**
 * 單一引擎執行後的回傳值 — 同時攜帶 findings（會列入 SARIF results）與 notices
 * （會列入 SARIF invocations.toolExecutionNotifications）。
 *
 * <p>引擎實作不應拋例外；遇到錯誤回傳 {@link #empty()} 並由 ScanOrchestrator 記錄 warning log。
 *
 * @param findings 此引擎發現的安全問題列表（可空）
 * @param notices  此引擎產出的 informational 注意事項列表（可空）
 *
 * @see SecurityAnalyzer#analyze(ScanContext)
 */
public record AnalysisOutput(List<SecurityFinding> findings, List<ScanNotice> notices) {

	/** 空輸出 — 用於引擎執行失敗或正常無發現的場景。 */
	public static AnalysisOutput empty() {
		return new AnalysisOutput(List.of(), List.of());
	}
}
