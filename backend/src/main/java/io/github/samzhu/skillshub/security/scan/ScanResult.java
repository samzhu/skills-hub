package io.github.samzhu.skillshub.security.scan;

import java.util.List;
import java.util.Map;

/**
 * 多引擎掃描的最終彙整結果 — 由 ScanOrchestrator 在所有引擎完成後組合而成，
 * 寫入 {@code skill_versions.{version}.riskAssessment} read model 欄位。
 *
 * <p>{@link #finalLevel} 採 max severity 規則：取所有 {@code findings} 中最嚴重的等級；
 * 若 findings 為空則為 {@link Severity#LOW}（與 S005 行為相容：純 markdown skill 為 LOW）。
 *
 * <p>{@link #sarif} 為 SARIF 2.1.0 結構序列化後的 {@code Map}（使用 Jackson 的
 * {@code convertValue} 把 typed records 轉成 map），讓 MongoDB driver 直接序列化進
 * Firestore document，不需額外的 codec。
 *
 * @param finalLevel 整體風險等級（HIGH/MEDIUM/LOW）
 * @param findings   全部引擎彙整後的 finding 列表（含 dedup，但本 record 不負責 dedup）
 * @param notices    全部引擎彙整後的 notice 列表
 * @param sarif      SARIF 2.1.0 文件（已序列化為 Map 結構）
 *
 * @see SecurityFinding
 * @see ScanNotice
 */
public record ScanResult(
		Severity finalLevel,
		List<SecurityFinding> findings,
		List<ScanNotice> notices,
		Map<String, Object> sarif
) {}
