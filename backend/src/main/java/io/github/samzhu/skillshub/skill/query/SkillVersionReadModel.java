package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 技能版本讀取模型（CQRS 查詢側投影）— 對應 PostgreSQL {@code skill_versions} 表。
 *
 * <p>由查詢側事件監聽器消費
 * {@link io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent} 後建立。
 * 每個 row 代表某技能的一個不可變版本快照。
 *
 * <p>{@code (skill_id, version)} UNIQUE constraint 由 V1 schema 強制；
 * {@code (skill_id, published_at DESC)} 索引（{@code idx_skill_versions_skill_published}）
 * 對應既有 {@code findBySkillIdOrderByPublishedAtDesc} derived query。
 *
 * <p>{@code riskAssessment} 在版本剛發佈時為 {@code null}，由 S010 的 ScanOrchestrator
 * 完成多引擎掃描後透過 {@link SkillVersionReadModelRepository#updateRiskAssessment}
 * 寫入。內容形如：
 * <pre>{@code
 * {
 *   "level": "HIGH",
 *   "findings": [...],     // List<SecurityFinding>
 *   "notices":  [...],     // List<ScanNotice>
 *   "sarif":    {...},     // SARIF 2.1.0 文件結構
 *   "scannedAt": Instant
 * }
 * }</pre>
 * 設計為 {@code Map<String, Object>} 而非 typed record，是因為 SARIF 內含多層巢狀結構，
 * 強型別 record 會與 SARIF 庫互相耦合；map + JSONB Converter 已足夠支援查詢需求。
 *
 * @param id             版本記錄唯一識別碼（UUID 字串）
 * @param skillId        所屬技能的聚合根識別碼
 * @param version        語意化版本號（SemVer，如 {@code 1.0.0}）
 * @param storagePath    套件在 GCS 中的完整物件路徑
 * @param fileSize       套件的位元組大小
 * @param frontmatter    SKILL.md frontmatter 解析後的 metadata 鍵值對（JSONB）
 * @param riskAssessment S010 多引擎掃描結果（含 SARIF），尚未掃描時為 {@code null}（JSONB）
 * @param publishedAt    版本發布時間戳
 */
@Table("skill_versions")
public record SkillVersionReadModel(
		@Id String id,
		@Column("skill_id") String skillId,
		@Column("version") String version,
		@Column("storage_path") String storagePath,
		@Column("file_size") long fileSize,
		@Column("frontmatter") Map<String, Object> frontmatter,
		@Column("risk_assessment") Map<String, Object> riskAssessment,
		@Column("published_at") Instant publishedAt
) implements Persistable<String> {

	/** 永遠回 true — projection 透過 save() 只建立新 row；risk_assessment 更新走 @Modifying @Query。 */
	@Override
	public boolean isNew() {
		return true;
	}

	@Override
	public String getId() {
		return id;
	}
}
