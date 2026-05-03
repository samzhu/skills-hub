package io.github.samzhu.skillshub.security;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * {@link FlagReadModel} 的 Spring Data JDBC Repository，提供 Flag 資料的持久化與
 * 查詢操作（對應 PostgreSQL {@code flags} 表）。
 */
public interface FlagReadModelRepository extends ListCrudRepository<FlagReadModel, String> {

	/**
	 * 依 Skill ID 查詢該 Skill 的所有 Flag，並依建立時間降冪排序（最新在前）。
	 *
	 * @param skillId 目標 Skill 的識別碼
	 * @return 對應的 Flag 清單，若無則為空列表
	 */
	List<FlagReadModel> findBySkillIdOrderByCreatedAtDesc(String skillId);

	/** S098e3 AC-5：per-skill 加 status filter。 */
	List<FlagReadModel> findBySkillIdAndStatusOrderByCreatedAtDesc(String skillId, String status);

	/** S098e3 AC-4：cross-skill 全部，按 createdAt desc。 */
	List<FlagReadModel> findAllByOrderByCreatedAtDesc();

	/** S098e3 AC-3：cross-skill 加 status filter。 */
	List<FlagReadModel> findByStatusOrderByCreatedAtDesc(String status);

	/**
	 * S098e3 AC-6：原生 SQL UPDATE — {@link FlagReadModel#isNew()} 永遠 true 不能走
	 * {@code save()} path（會誤觸 INSERT 衝主鍵）。{@code @Modifying @Query} 是 Flag
	 * UPDATE 唯一合法路徑（mirror Skill {@code updateRiskLevel} S014 同 pattern）。
	 *
	 * @return UPDATE row count（0 表示 flagId 不存在；service 層判 404）
	 */
	@Modifying
	@Query("UPDATE flags SET status = :status WHERE id = :flagId")
	int updateStatus(@Param("flagId") String flagId, @Param("status") String status);

}
