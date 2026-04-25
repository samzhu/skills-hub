package io.github.samzhu.skillshub.security;

import java.util.List;

/**
 * {@link RiskScanner} 掃描後的彙整結果。
 *
 * @param level    整體風險等級，由所有 {@code findings} 中最嚴重的類型決定
 * @param findings 所有偵測到的風險發現清單；若無問題則為空列表
 */
public record ScanResult(
		RiskLevel level,
		List<RiskFinding> findings
) {}
