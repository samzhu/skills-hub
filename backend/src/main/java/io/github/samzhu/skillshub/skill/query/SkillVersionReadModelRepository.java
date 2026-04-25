package io.github.samzhu.skillshub.skill.query;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 技能版本讀取模型的資料存取介面 — 對應 Firestore（MongoDB driver）{@code skill_versions} 集合。
 *
 * <p>繼承 {@link MongoRepository} 取得標準 CRUD 能力，並擴充版本查詢方法。
 * 僅限查詢側使用；命令側應操作 Event Store。
 *
 * @see SkillVersionReadModel
 */
public interface SkillVersionReadModelRepository extends MongoRepository<SkillVersionReadModel, String> {

	/**
	 * 依技能 ID 查詢所有版本，依發布時間由新至舊排序。
	 *
	 * @param skillId 目標技能的聚合根識別碼
	 * @return 版本讀取模型清單，若無任何版本則回傳空集合
	 */
	List<SkillVersionReadModel> findBySkillIdOrderByPublishedAtDesc(String skillId);

}
