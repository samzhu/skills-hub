package io.github.samzhu.skillshub.skill.command;

import java.util.Map;

/**
 * 發布技能版本命令 — 攜帶將技能套件與版本資訊關聯所需的全部資料。
 *
 * <p>由 {@code SkillCommandController} 在 SKILL.md 通過 {@link io.github.samzhu.skillshub.skill.validation.SkillValidator}
 * 驗證、套件上傳至 GCS 完成後傳入 {@code SkillCommandService#publishVersion}。
 * 若版本號已存在則拋出 {@link VersionExistsException}。
 *
 * @param skillId     目標技能的聚合根識別碼（UUID）
 * @param version     要發布的語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath 套件在 GCS 的完整物件路徑
 * @param fileSize    套件的位元組大小
 * @param fileCount   zip 內 entry 數（排除 directories）— S098a3-2；upload pipeline
 *                    透過 {@link io.github.samzhu.skillshub.storage.PackageService#countEntries}
 *                    計算後填入；0 表 unknown / legacy
 * @param frontmatter 從 SKILL.md YAML frontmatter 解析出的 metadata 鍵值對
 */
public record PublishVersionCommand(
		String skillId,
		String version,
		String storagePath,
		long fileSize,
		int fileCount,
		Map<String, Object> frontmatter
) {}
