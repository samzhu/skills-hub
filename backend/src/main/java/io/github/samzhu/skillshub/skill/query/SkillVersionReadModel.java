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
 * <p>{@code riskAssessment} 在版本剛發佈時為 {@code null}，由 S010 的 ScanOrchestrator
 * 完成多引擎掃描後直接以 MongoTemplate.updateFirst 寫入。內容形如：
 * <pre>{@code
 * {
 *   "level": "HIGH",
 *   "findings": [...],     // List<SecurityFinding> 序列化
 *   "notices":  [...],     // List<ScanNotice> 序列化
 *   "sarif":    {...},     // SARIF 2.1.0 文件結構
 *   "scannedAt": Instant
 * }
 * }</pre>
 * 設計為 {@code Map<String, Object>} 而非 typed record，是因為 SARIF 內含多層巢狀結構，
 * 強型別 record 會與 SARIF 庫互相耦合；map + Mongo 內建序列化已足夠支援查詢需求。
 *
 * @param id             版本記錄唯一識別碼（UUID）
 * @param skillId        所屬技能的聚合根識別碼
 * @param version        語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath    套件在 GCS 中的完整物件路徑
 * @param fileSize       套件的位元組大小
 * @param frontmatter    SKILL.md frontmatter 解析後的 metadata 鍵值對
 * @param riskAssessment S010 多引擎掃描結果（含 SARIF），尚未掃描時為 {@code null}
 * @param publishedAt    版本發布時間戳
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
		Map<String, Object> riskAssessment,
		Instant publishedAt
) {}
