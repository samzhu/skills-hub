package io.github.samzhu.skillshub.security;

import java.util.List;

import org.springframework.data.repository.ListCrudRepository;

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

}
