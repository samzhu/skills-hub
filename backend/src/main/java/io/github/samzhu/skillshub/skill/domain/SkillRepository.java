package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Skill aggregate 的 Spring Data JDBC repository（S024 引入；ADR-002 Phase 2）。
 *
 * <p>繼承 {@link ListCrudRepository} 取得標準 CRUD（save / findById / findAll /
 * count / deleteById）。{@code save()} 透過 Spring Data
 * {@code EventPublishingRepositoryProxyPostProcessor} 攔截器，在 SQL 完成後自動
 * publish {@link AbstractAggregateRoot#domainEvents()} 內已 register 的 events 至
 * Modulith {@code event_publication} outbox（同 transaction）— 詳見
 * {@code docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md §3}。
 *
 * <p>S024 T1 — minimal interface（僅 CRUD + legacy author/name 查詢）；後續 task 補：
 * <ul>
 *   <li>T5：{@code @Modifying @Query int updateRiskLevel(...)} — ScanOrchestrator
 *       cross-aggregate projection（risk_level 為 SkillVersion 衍生資料，
 *       不走 aggregate event publish path）</li>
 *   <li>T5：{@code @Query findAccessibleByAclEntries(String[])} — ACL-aware listing</li>
 * </ul>
 *
 * <p>S024 ship 後將取代 {@code SkillReadModelRepository}（T5 整檔刪除）。
 *
 * @see Skill
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
public interface SkillRepository extends ListCrudRepository<Skill, String> {

    /**
     * S096c/S176 — 依 (author, name) 組合查詢 Skill；用於 `/skills/:author/:name` legacy alias。
     * Canonical route is id-based. case-insensitive 比對對齊 GitHub/npm/Docker Hub 慣例
     *（`Platform-Team` ≡ `platform-team`）。
     *
     * <p>S176 移除 {@code skills.name UNIQUE} 後，同一 author/name 可能有多筆歷史資料；
     * legacy alias 固定回最新建立 row，避免 PostgreSQL heap/page order 影響 {@code LIMIT 1}。
     */
    @Query("""
            SELECT id,
                   name,
                   description,
                   author,
                   category,
                   category_display,
                   author_name_snapshot,
                   status,
                   latest_version,
                   risk_level,
                   download_count,
                   average_rating,
                   review_count,
                   acl_entries,
                   is_public,
                   owner_id,
                   created_at,
                   updated_at,
                   version
            FROM skills
            WHERE LOWER(author) = LOWER(:author)
              AND LOWER(name) = LOWER(:name)
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    Optional<Skill> findByAuthorAndName(@Param("author") String author, @Param("name") String name);

    /**
     * S024 T5 — Cross-aggregate projection: 由 {@code ScanOrchestrator} multi-engine
     * scan pipeline 完成後寫入 {@code skills.risk_level}（衍生自 {@code SkillVersion.riskAssessment}）。
     *
     * <p><b>不走 aggregate event publish path</b>：{@code risk_level} 是 SkillVersion 衍生資料、
     * 非 Skill aggregate 自身 invariant；維持 v1.5.0 行為以最小化 API contract 影響
     * （API JSON 仍 expose {@code riskLevel} 欄位）。
     *
     * <p>取代既有 {@code SkillReadModelRepository.updateRiskLevel}（T7 read-model 刪除後唯一保留路徑）。
     *
     * @param id        技能 aggregate ID
     * @param riskLevel 評估結果（LOW / MEDIUM / HIGH）
     * @param ts        更新時間戳（同步寫入 updated_at）
     * @return 更新的 row 數（找不到 id 時為 0）
     */
    @Modifying
    @Query("UPDATE skills SET risk_level = :riskLevel, updated_at = :ts WHERE id = :id")
    int updateRiskLevel(@Param("id") String id, @Param("riskLevel") String riskLevel, @Param("ts") Instant ts);

    /**
     * S187-T06 — 新版本上傳後要重新掃描，不可沿用上一版 {@code risk_level}。
     */
    @Modifying
    @Query("UPDATE skills SET risk_level = NULL, updated_at = :ts WHERE id = :id")
    int clearRiskLevel(@Param("id") String id, @Param("ts") Instant ts);

    /**
     * S076: 原子計數遞增（取代 aggregate {@code recordDownload + save} 的 read-modify-write
     * 樂觀鎖路徑）— 並行下載同 skill 不再觸發 OptimisticLockingFailureException。
     *
     * <p>Counter 增量不是 state-machine concern；不需「先看到他人 update 才合併」的語意。
     * 走原子 SQL UPDATE 讓 PG row-level lock 處理併發，避開 aggregate {@code @Version} 衝突。
     *
     * <p><b>不走 aggregate event publish path</b>：caller 端用 ApplicationEventPublisher 顯式發
     * {@code SkillDownloadedEvent}。Modulith Event Publication Registry 會 intercept transactional
     * context 中所有 events 寫入 outbox，與 {@code @DomainEvents} 路徑共用 at-least-once 保證。
     *
     * @param id 技能 aggregate ID
     * @param ts 更新時間戳（同步寫入 updated_at）
     * @return 更新的 row 數（找不到 id 時為 0）
     */
    @Modifying
    @Query("UPDATE skills SET download_count = download_count + 1, updated_at = :ts WHERE id = :id")
    int incrementDownloadCount(@Param("id") String id, @Param("ts") Instant ts);

    /**
     * S096f2 — 批次查詢指定 status 的 skills；用於 CollectionService.create 預檢
     * skillIds 全 PUBLISHED。caller 比對 returned skill IDs 與輸入 list 找 invalid。
     *
     * <p>Single-property sort 不觸發 Spring Boot 4.0.6 AOT codegen compound-sort bug
     * — derived query naming 安全，不需 `@Query` workaround。
     */
    List<Skill> findAllByIdInAndStatus(Collection<String> ids, SkillStatus status);
}
