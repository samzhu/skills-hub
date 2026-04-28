package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * 技能讀取模型的資料存取介面 — 對應 PostgreSQL {@code skills} 表（Spring Data JDBC）。
 *
 * <p>繼承 {@link ListCrudRepository} 取得標準 CRUD 與分頁查詢能力。
 * 僅限查詢側使用；命令側應操作 Event Store。
 *
 * @see SkillReadModel
 */
public interface SkillReadModelRepository extends ListCrudRepository<SkillReadModel, String> {

	/**
	 * 累加 skill 下載計數（atomic UPDATE，避免 read-modify-write race condition）。
	 *
	 * <p>用於 {@code SkillProjection.on(SkillDownloadedEvent)}；多個並發 download
	 * 事件可正確累加（PostgreSQL row-level lock + UPDATE 表達式原子化）。
	 *
	 * @param id 技能 ID
	 * @param ts 更新時間戳（同步寫入 updated_at）
	 * @return 更新的 row 數（找不到 id 時為 0）
	 */
	@Modifying
	@Query("UPDATE skills SET download_count = download_count + 1, updated_at = :ts WHERE id = :id")
	int incrementDownloadCount(@Param("id") String id, @Param("ts") Instant ts);

	/**
	 * 版本發佈時更新 skill 的 latest_version（atomic UPDATE）。
	 *
	 * <p>用於 {@code SkillProjection.on(SkillVersionPublishedEvent)} — 取代既有
	 * read-modify-write，避免 Spring Data JDBC 對 record 預設「id 非 null → UPDATE」
	 * 行為與 INSERT 路徑混淆。
	 *
	 * @param id            技能 ID
	 * @param latestVersion 新發佈的版本字串
	 * @param ts            更新時間戳（同步寫入 updated_at）
	 * @return 更新的 row 數
	 */
	@Modifying
	@Query("UPDATE skills SET latest_version = :latestVersion, updated_at = :ts WHERE id = :id")
	int updateLatestVersion(@Param("id") String id, @Param("latestVersion") String latestVersion, @Param("ts") Instant ts);

	/**
	 * 更新 skill 的 risk_level（S010 ScanOrchestrator pipeline 完成後寫入）。
	 *
	 * <p>S005 行為相容 — read model 同一欄位（risk_level）；ScanOrchestrator 在
	 * pipeline 結束後直接更新此欄位，不發第二個 SkillRiskAssessed application event
	 * 以避免循環依賴。
	 *
	 * @param id        技能 ID
	 * @param riskLevel 評估結果（LOW / MEDIUM / HIGH）
	 * @param ts        更新時間戳（同步寫入 updated_at）
	 * @return 更新的 row 數（找不到 id 時為 0）
	 */
	@Modifying
	@Query("UPDATE skills SET risk_level = :riskLevel, updated_at = :ts WHERE id = :id")
	int updateRiskLevel(@Param("id") String id, @Param("riskLevel") String riskLevel, @Param("ts") Instant ts);

	/**
	 * S018：更新 skill 的 status（atomic UPDATE）。
	 *
	 * <p>用於 {@code SkillProjection} 對 SkillVersionPublishedEvent 首版 transition（DRAFT→PUBLISHED）
	 * + SkillSuspendedEvent / SkillReactivatedEvent 的 read-side projection。
	 *
	 * @param id     技能 ID
	 * @param status 新狀態（{@code DRAFT} / {@code PUBLISHED} / {@code SUSPENDED}）
	 * @param ts     更新時間戳（同步寫入 updated_at）
	 * @return 更新的 row 數（找不到 id 時為 0）
	 */
	@Modifying
	@Query("UPDATE skills SET status = :status, updated_at = :ts WHERE id = :id")
	int updateStatus(@Param("id") String id, @Param("status") String status, @Param("ts") Instant ts);

	/**
	 * S016：追加 ACL entry（型如 {@code "type:principal:permission"}）至 {@code skills.acl_entries}。
	 *
	 * <p>用於 {@code SkillProjection.on(SkillAclGrantedEvent)} read-side 投影；
	 * {@code WHERE NOT (acl_entries @> to_jsonb(:entry))} 保證冪等 — 同一 entry 重複事件不疊加。
	 *
	 * @param id    技能 ID
	 * @param entry 完整字串（如 {@code "group:engineering:read"}）
	 * @param ts    更新時間戳
	 * @return 更新的 row 數（0 = entry 已存在或 skill 不存在；非 throw，由 listener 視情況 log）
	 */
	@Modifying
	@Query("""
			UPDATE skills
			   SET acl_entries = acl_entries || to_jsonb(:entry),
			       updated_at = :ts
			 WHERE id = :id
			   AND NOT (acl_entries @> to_jsonb(:entry))
			""")
	int appendAclEntry(@Param("id") String id, @Param("entry") String entry, @Param("ts") Instant ts);

	/**
	 * S016：從 {@code skills.acl_entries} 移除指定字串 entry。
	 *
	 * <p>用 {@code jsonb_array_elements_text} + {@code jsonb_agg} 重組（比 jsonb {@code -} operator
	 * 對純字串 array 更穩健）；{@code COALESCE(..., '[]'::jsonb)} 處理移除後變空 array 的 NULL 回傳。
	 *
	 * @param id    技能 ID
	 * @param entry 要移除的字串
	 * @param ts    更新時間戳
	 * @return 更新的 row 數（0 = skill 不存在）
	 */
	@Modifying
	@Query("""
			UPDATE skills
			   SET acl_entries = COALESCE(
			       (SELECT jsonb_agg(elem)
			          FROM jsonb_array_elements_text(acl_entries) elem
			         WHERE elem != :entry),
			       '[]'::jsonb),
			       updated_at = :ts
			 WHERE id = :id
			""")
	int removeAclEntry(@Param("id") String id, @Param("entry") String entry, @Param("ts") Instant ts);
}
