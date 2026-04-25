package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 技能版本讀取模型（CQRS 查詢側投影）— 對應 Firestore {@code skill_versions} 集合的文件結構。
 *
 * <p>由查詢側事件監聽器消費
 * {@link io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent} 後建立。
 * 每個文件代表某技能的一個不可變版本快照。
 *
 * @param id          版本記錄唯一識別碼（UUID）
 * @param skillId     所屬技能的聚合根識別碼
 * @param version     語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath 套件在 GCS 中的完整物件路徑
 * @param fileSize    套件的位元組大小
 * @param frontmatter SKILL.md frontmatter 解析後的 metadata 鍵值對
 * @param publishedAt 版本發布時間戳
 */
@Document("skill_versions")
// Firestore composite index required (create in Firestore Console):
//   collection: skill_versions  fields: skillId ASC, publishedAt DESC
// Used by: findBySkillIdOrderByPublishedAtDesc
// NOTE: auto-index-creation=false — Firestore 不支援透過 MongoDB wire protocol 建立 index。
@CompoundIndex(def = "{'skillId': 1, 'publishedAt': -1}", name = "idx_skillId_publishedAt")
public record SkillVersionReadModel(
		@Id String id,
		String skillId,
		String version,
		String storagePath,
		long fileSize,
		Map<String, Object> frontmatter,
		Instant publishedAt
) {}
