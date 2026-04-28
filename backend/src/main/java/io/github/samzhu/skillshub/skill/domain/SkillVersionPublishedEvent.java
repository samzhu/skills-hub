package io.github.samzhu.skillshub.skill.domain;

import java.util.List;
import java.util.Map;

/**
 * 技能版本發布領域事件 — 當 SKILL.md 通過驗證並成功上傳至 GCS 後發布。
 *
 * <p>由 {@code SkillCommandService#publishVersion} 產生。
 * 查詢側消費此事件以建立 {@code SkillVersionReadModel} 投影，
 * 安全模組則另行訂閱以觸發非同步風險評估。
 *
 * @param aggregateId  技能聚合根的唯一識別碼（UUID）
 * @param version      語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath  套件在 GCS 中的完整物件路徑
 * @param fileSize     上傳套件的位元組大小
 * @param frontmatter  從 SKILL.md YAML frontmatter 解析出的 metadata 鍵值對
 * @param allowedTools S018：從 frontmatter {@code allowed-tools} space-separated 字串解析後的
 *                     typed payload 欄位（per agentskills.io spec；空 list 為合法）。
 *                     既有 events 在 store 中無此 field，replay 時 payload.get("allowed-tools")
 *                     為 null 由 caller fallback empty list（per spec §6 Open Risks #2）。
 */
public record SkillVersionPublishedEvent(
		String aggregateId,
		String version,
		String storagePath,
		long fileSize,
		Map<String, Object> frontmatter,
		List<String> allowedTools
) {}
