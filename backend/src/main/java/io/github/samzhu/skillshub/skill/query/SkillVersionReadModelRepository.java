package io.github.samzhu.skillshub.skill.query;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * 技能版本讀取模型的資料存取介面 — 對應 PostgreSQL {@code skill_versions} 表
 * （Spring Data JDBC）。
 *
 * <p>繼承 {@link ListCrudRepository} 取得標準 CRUD 能力，並擴充版本查詢方法。
 * 僅限查詢側使用；命令側應操作 Event Store。
 *
 * @see SkillVersionReadModel
 */
public interface SkillVersionReadModelRepository extends ListCrudRepository<SkillVersionReadModel, String> {

	/**
	 * 依技能 ID 查詢所有版本，依發布時間由新至舊排序。
	 *
	 * @param skillId 目標技能的聚合根識別碼
	 * @return 版本讀取模型清單，若無任何版本則回傳空集合
	 */
	List<SkillVersionReadModel> findBySkillIdOrderByPublishedAtDesc(String skillId);

	/**
	 * S010 ScanOrchestrator 完成多引擎掃描後寫入 risk_assessment。
	 *
	 * <p>採 {@code @Modifying @Query} 而非 ScanOrchestrator 內 raw {@code jdbc.update} —
	 * 設計原則：CRUD / single-row UPDATE 走 Spring Data JDBC、僅動態 query 才下 JdbcTemplate。
	 *
	 * <p>{@code CAST(:riskJson AS jsonb)} 由 PostgreSQL 完成字串 → JSONB 轉型；
	 * 呼叫端負責用 {@code ObjectMapper.writeValueAsString(riskMap)} 序列化。
	 *
	 * @param skillId  技能 aggregate ID
	 * @param version  版本字串
	 * @param riskJson Jackson 序列化後的 risk assessment JSON 字串
	 * @return 更新的 row 數（找不到 (skillId, version) 時為 0）
	 */
	@Modifying
	@Query("""
			UPDATE skill_versions
			   SET risk_assessment = CAST(:riskJson AS jsonb)
			 WHERE skill_id = :skillId AND version = :version
			""")
	int updateRiskAssessment(
			@Param("skillId") String skillId,
			@Param("version") String version,
			@Param("riskJson") String riskJson);

}
