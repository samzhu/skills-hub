package io.github.samzhu.skillshub.skill.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技能版本發布領域事件 — 當 SKILL.md 通過驗證並成功上傳至 GCS 後發布。
 *
 * <p>由 {@code SkillCommandService#publishVersion} 產生。
 * 查詢側消費此事件以建立 {@code SkillVersionReadModel} 投影，
 * 安全模組則另行訂閱以觸發非同步風險評估。
 *
 * <p>S023 加入 {@code sourceEventId} 欄位作為 ScanOrchestrator 的 idempotency key —
 * async listener retry 場景下可避免重複觸發完整 multi-engine scan pipeline（成本 + 一致性）。
 * 詳見 {@link io.github.samzhu.skillshub.security.scan.ScanOrchestrator#on}。
 *
 * @param aggregateId   技能聚合根的唯一識別碼（UUID）
 * @param version       語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath   套件在 GCS 中的完整物件路徑
 * @param fileSize      上傳套件的位元組大小
 * @param frontmatter   從 SKILL.md YAML frontmatter 解析出的 metadata 鍵值對
 * @param allowedTools  S018：從 frontmatter {@code allowed-tools} space-separated 字串解析後的
 *                      typed payload 欄位（per agentskills.io spec；空 list 為合法）。
 *                      既有 events 在 store 中無此 field，replay 時 payload.get("allowed-tools")
 *                      為 null 由 caller fallback empty list（per spec §6 Open Risks #2）。
 * @param sourceEventId S023：事件唯一識別碼（UUID 字串）— 用於 ScanOrchestrator 重複觸發保護。
 *                      寫入 {@code skill_versions.risk_assessment->>'sourceEventId'}；retry 時
 *                      檢查此 key 即可知 scan 是否已完成。
 */
public record SkillVersionPublishedEvent(
		String aggregateId,
		String version,
		String storagePath,
		long fileSize,
		Map<String, Object> frontmatter,
		List<String> allowedTools,
		String sourceEventId
) {

	/**
	 * Factory — 自動產生 {@code sourceEventId} 為新 UUID 字串。
	 *
	 * <p>絕大多數 publisher 用此 factory；retry / replay 場景才直接呼叫
	 * canonical constructor 沿用既有 sourceEventId。
	 */
	public static SkillVersionPublishedEvent of(String aggregateId, String version,
			String storagePath, long fileSize,
			Map<String, Object> frontmatter, List<String> allowedTools) {
		return new SkillVersionPublishedEvent(aggregateId, version, storagePath, fileSize,
				frontmatter, allowedTools, UUID.randomUUID().toString());
	}
}
