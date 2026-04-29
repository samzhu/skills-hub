package io.github.samzhu.skillshub.skill.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * SkillVersion aggregate 的 Spring Data JDBC repository（S024 引入；ADR-002 Phase 2）。
 *
 * <p>{@code save()} 透過 Spring Data
 * {@code EventPublishingRepositoryProxyPostProcessor} 在 SQL 完成後自動 publish
 * {@code AbstractAggregateRoot.domainEvents()} 內已 register 的事件
 * （如 {@link SkillVersionPublishedEvent} / {@link SkillRiskAssessedEvent}）至 Modulith
 * {@code event_publication} outbox（同 transaction）。
 *
 * @see SkillVersion
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
public interface SkillVersionRepository extends ListCrudRepository<SkillVersion, String> {

    /**
     * 檢查指定 skillId + version 組合是否已存在 — 由 {@code SkillCommandService.publishVersion}
     * 在 {@code @Transactional} boundary 內預檢；命中則拋 {@code VersionExistsException}
     * 友好錯誤（先於 DB UNIQUE constraint 觸發）。
     *
     * <p>schema 層 {@code UNIQUE (skill_id, version)}（V1 既有索引）為終極兜底 —
     * bypass service 直接呼叫 repo.save 重複 → {@code DataIntegrityViolationException}。
     */
    boolean existsBySkillIdAndVersion(String skillId, String version);

    /**
     * 查指定 skill 的所有版本，依 publishedAt 降序 — 取代既有
     * {@code SkillVersionReadModelRepository.findBySkillIdOrderByPublishedAtDesc}（T5 刪除）。
     *
     * <p>schema 層 {@code idx_skill_versions_skill_published} index（V1 既有）支撐 ORDER BY DESC。
     */
    List<SkillVersion> findBySkillIdOrderByPublishedAtDesc(String skillId);

    /**
     * 取單一版本 — 用於 ScanOrchestrator T5 改造（load → attachRiskAssessment → save）+
     * SkillQueryController 「下載指定版本」場景。
     */
    Optional<SkillVersion> findBySkillIdAndVersion(String skillId, String version);

    /**
     * S023 idempotency check — 是否已存在 risk_assessment 對應指定 sourceEventId？
     *
     * <p>用於 ScanOrchestrator {@code @ApplicationModuleListener} retry 場景：若已掃描過
     * 該事件對應的版本，跳過完整 scan pipeline（成本 + 一致性考量；per S023 spec §4.9）。
     *
     * <p>SQL 用 {@code risk_assessment->>'sourceEventId' = :sourceEventId} 比對 JSONB 內欄位；
     * 若 risk_assessment 為 NULL（從未掃描）→ 子查詢回 false。
     *
     * <p>從 {@code SkillVersionReadModelRepository} 既有實作移植（T5 刪 ReadModel
     * repository 後此方法為唯一保留路徑）。
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM skill_versions
                 WHERE skill_id = :skillId
                   AND version = :version
                   AND risk_assessment->>'sourceEventId' = :sourceEventId
            )
            """)
    boolean hasRiskAssessmentFromEvent(
            @Param("skillId") String skillId,
            @Param("version") String version,
            @Param("sourceEventId") String sourceEventId);
}
