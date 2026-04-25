package io.github.samzhu.skillshub.skill.query;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 技能讀取模型的資料存取介面 — 對應 Firestore（MongoDB driver）{@code skills} 集合。
 *
 * <p>繼承 {@link MongoRepository} 取得標準 CRUD 與分頁查詢能力。
 * 僅限查詢側（{@code SkillProjectionService} 等）使用；命令側應操作 Event Store。
 *
 * @see SkillReadModel
 */
public interface SkillReadModelRepository extends MongoRepository<SkillReadModel, String> {
}
